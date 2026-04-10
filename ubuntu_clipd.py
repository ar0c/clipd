#!/usr/bin/env python3
"""
Clipd 桌面端 - Ubuntu 端剪切板同步守护进程
- 接收来自 Android 的文字 / 图片，写入本机剪切板
- 监听本机剪切板变化，推送到 Android
- mDNS + UDP 广播自动发现 Android，无需手动输入 IP
- 系统托盘图标、桌面通知、二维码配对
"""

import cgi
import glob as _glob
import hashlib
import http.server
import io
import os
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

UBUNTU_PORT     = 8888
ANDROID_PORT    = 8889
DISCOVERY_PORT  = 8890
BROADCAST_INTERVAL = 3
APP_ID          = "clipd"
APP_VERSION     = "1.0.97"
APP_DISPLAY_NAME = "Clipd 桌面端"
_notify_inited  = False

_last_hash     = ""
_lock          = threading.Lock()
_android_ip                = None
_android_lock              = threading.Lock()
_android_last_seen         = 0.0
_android_state             = "offline"   # offline | connected | suspect
ANDROID_SUSPECT_SEC        = 60          # 心跳延迟容忍：超过则进入 suspect，但保留 IP
ANDROID_HARD_OFFLINE_SEC   = 300         # 真离线：清空 IP、广播 disconnect
_wl_copy_proc  = None
_wl_copy_lock  = threading.Lock()
_tray_icon     = None

# ── 统计与日志 ──
_stats = {"sent": 0, "recv": 0, "notif": 0, "start_time": None}
_log_lines: list[str] = []
_log_lock  = threading.Lock()
_ui_update_fn = None   # GUI 回调，由 GTK 窗口设置


def _local_ips() -> set[str]:
    """枚举本机所有 IPv4 地址（包括 LAN / docker / 虚拟接口），用于排除自身请求。"""
    ips = {"127.0.0.1"}
    try:
        out = subprocess.check_output(["ip", "-4", "-o", "addr"], text=True)
        import re as _re
        for line in out.splitlines():
            m = _re.search(r"inet (\d+\.\d+\.\d+\.\d+)", line)
            if m:
                ips.add(m.group(1))
    except Exception:
        pass
    return ips


_STATE_FILE = os.path.expanduser("~/.config/clipd/state")


def _save_last_android_ip(ip: str):
    try:
        os.makedirs(os.path.dirname(_STATE_FILE), exist_ok=True)
        with open(_STATE_FILE, "w") as f:
            f.write(ip)
    except Exception:
        pass


def _load_last_android_ip() -> str | None:
    try:
        with open(_STATE_FILE) as f:
            ip = f.read().strip()
            return ip or None
    except Exception:
        return None


def _add_log(msg: str):
    ts = time.strftime("%H:%M:%S")
    line = f"[{ts}] {msg}"
    with _log_lock:
        _log_lines.append(line)
        if len(_log_lines) > 500:
            _log_lines[:] = _log_lines[-300:]
    print(f"[clipd] {msg}", flush=True)
    if _ui_update_fn:
        try:
            _ui_update_fn()
        except Exception:
            pass


# ── 工具函数 ──────────────────────────────────────────────────────────────────

def md5(data: bytes | str) -> str:
    if isinstance(data, str):
        data = data.encode()
    return hashlib.md5(data).hexdigest()[:12]


def get_lan_ip() -> str:
    """优先返回真实 LAN IP（192.168/10 段），排除 VPN/代理虚拟接口"""
    import re as _re

    def _is_lan(ip: str) -> bool:
        return ip.startswith("192.168.") or (
            ip.startswith("10.") and not ip.startswith("10.0.3.")  # 排除 lxcbr0
        )

    def _is_virt_iface(name: str) -> bool:
        return name.startswith(("lo", "docker", "br-", "veth", "lxc")) or \
               name in ("tailscale0", "Mihomo", "tun0", "wg0", "tel0")

    # 优先从 ip addr 列出所有接口
    try:
        out = subprocess.check_output(["ip", "-o", "-4", "addr"], text=True, stderr=subprocess.DEVNULL)
        # 格式: idx  iface  inet ip/prefix  ...
        candidates = []
        for line in out.splitlines():
            parts = line.split()
            if len(parts) < 4:
                continue
            iface = parts[1]
            ip = parts[3].split("/")[0]
            if not _is_virt_iface(iface):
                candidates.append((iface, ip))
        # 先找 192.168.x.x，再找其他非虚拟 IP
        for _, ip in candidates:
            if _is_lan(ip):
                return ip
        for _, ip in candidates:
            if ip != "127.0.0.1":
                return ip
    except Exception:
        pass
    # 兜底
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.168.1.1", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return socket.gethostbyname(socket.gethostname())


def notify(msg: str, img_data: bytes = None):
    icon = "edit-paste"
    cleanup = None
    if img_data:
        try:
            from PIL import Image
            tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False, prefix="clipd_")
            thumb = Image.open(io.BytesIO(img_data))
            thumb.thumbnail((256, 256))
            thumb.save(tmp.name, "PNG")
            tmp.close()
            icon = tmp.name
            cleanup = tmp.name
        except Exception:
            pass
    _send_notification(APP_DISPLAY_NAME, msg, icon=icon, expire_time=4000)
    if cleanup:
        def _rm():
            time.sleep(6)
            try: os.unlink(cleanup)
            except: pass
        threading.Thread(target=_rm, daemon=True).start()


def _extract_code(text: str) -> str:
    """从短信/通知文本中提取验证码（4-8位纯数字）"""
    import re
    m = re.search(r'(?:验证码|校验码|确认码|动态码|短信码|code)[：:\s]*(\d{4,8})', text, re.IGNORECASE)
    if m: return m.group(1)
    m = re.search(r'(\d{4,8})\s*(?:验证码|校验码|确认码|动态码|为.*验证)', text)
    if m: return m.group(1)
    # 通用：文本中唯一的 4-8 位数字串
    nums = re.findall(r'\b(\d{4,8})\b', text)
    if len(nums) == 1: return nums[0]
    return ""


def _send_notification(summary: str, body: str = "", icon: str = "edit-paste",
                       expire_time: int = 4000, open_dir: str = "",
                       copy_text: str = ""):
    if copy_text:
        def _notify_copy():
            cmd = ["notify-send", summary, body or "",
                   f"--icon={icon}", f"--app-name={APP_DISPLAY_NAME}",
                   "--hint=string:desktop-entry:clipd",
                   "-A", f"copy=复制验证码 {copy_text}"]
            try:
                r = subprocess.run(cmd, capture_output=True, text=True)
                if r.returncode == 0 and "copy" in (r.stdout or ""):
                    subprocess.run(["xclip", "-selection", "clipboard"],
                                   input=copy_text.encode(), check=True)
                    _send_notification("验证码已复制", copy_text,
                                       icon="edit-copy", expire_time=2000)
            except Exception:
                pass
        threading.Thread(target=_notify_copy, daemon=True).start()
        return

    if open_dir:
        # notify-send --action 支持点击回调，需阻塞等待，放后台线程
        def _notify_with_action():
            cmd = ["notify-send", summary, body or "",
                   f"--icon={icon}", f"--app-name={APP_DISPLAY_NAME}",
                   "--hint=string:desktop-entry:clipd",
                   "-A", "default=打开目录"]
            try:
                r = subprocess.run(cmd, capture_output=True, text=True)
                if r.returncode == 0 and "default" in (r.stdout or ""):
                    subprocess.Popen(["xdg-open", open_dir],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass
        threading.Thread(target=_notify_with_action, daemon=True).start()
        return

    global _notify_inited
    try:
        import gi
        gi.require_version("Notify", "0.7")
        from gi.repository import Notify, GLib

        if not _notify_inited:
            Notify.init(APP_DISPLAY_NAME)
            _notify_inited = True

        n = Notify.Notification.new(summary, body or None, icon)
        n.set_app_name(APP_DISPLAY_NAME)
        try:
            n.set_hint("desktop-entry", GLib.Variant("s", "clipd.desktop"))
        except Exception:
            pass
        n.set_timeout(expire_time)
        n.show()
        return
    except Exception:
        pass

    cmd = ["notify-send", APP_DISPLAY_NAME, body or summary, f"--icon={icon}", f"--expire-time={expire_time}"]
    subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# ── 系统托盘 ──────────────────────────────────────────────────────────────────

def _make_tray_image(connected: bool):
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    color = (46, 204, 113, 255) if connected else (149, 165, 166, 255)
    draw.ellipse([6, 6, 58, 58], fill=color)
    return img


def _tray_menu(status_text: str):
    import pystray
    return pystray.Menu(
        pystray.MenuItem(status_text, None, enabled=False),
        pystray.MenuItem(f"v{APP_VERSION}", None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("显示配对码", lambda icon, item: show_qr()),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("退出", lambda icon, item: (icon.stop(), os._exit(0))),
    )


def _tray_status(connected: bool, label: str = "") -> str:
    if label:
        return f"{APP_DISPLAY_NAME} - {label}"
    return f"{APP_DISPLAY_NAME} - {'已连接' if connected else '等待连接'}"


def create_tray():
    global _tray_icon
    try:
        import pystray
        _tray_icon = pystray.Icon(
            APP_ID,
            _make_tray_image(False),
            _tray_status(False),
            _tray_menu(_tray_status(False)),
        )
        _tray_icon.run_detached()
        print("[clipd] 托盘图标已启动")
    except ImportError:
        print("[clipd] pystray 未安装，跳过托盘（pip3 install pystray）")
    except Exception as e:
        print(f"[clipd] 托盘初始化失败: {e}")


def update_tray(connected: bool, label: str = ""):
    if _tray_icon is None:
        return
    try:
        status = _tray_status(connected, label)
        _tray_icon.icon  = _make_tray_image(connected)
        _tray_icon.title = status
        _tray_icon.menu  = _tray_menu(status)
    except Exception as e:
        print(f"[clipd] 托盘更新失败: {e}")


# ── 配对二维码 ────────────────────────────────────────────────────────────────

def show_qr():
    try:
        import qrcode
        ip = get_lan_ip()
        content = f"clipd://connect?ip={ip}&port={UBUNTU_PORT}"
        qr = qrcode.make(content)
        tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False, prefix="clipd_qr_")
        qr.save(tmp.name)
        tmp.close()
        subprocess.Popen(["xdg-open", tmp.name],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"[clipd] QR 配对码已打开 ({content})")
    except ImportError:
        notify("请安装 qrcode: pip3 install qrcode[pil]")
    except Exception as e:
        notify(f"生成二维码失败: {e}")


# ── 剪切板操作（支持 Wayland / X11）────────────────────────────────────────────

def _is_wayland() -> bool:
    return bool(os.environ.get("WAYLAND_DISPLAY"))


def _xclip_env() -> dict:
    env = os.environ.copy()
    env["DISPLAY"] = env.get("DISPLAY", ":0")
    uid = os.getuid()
    matches = sorted(_glob.glob(f"/run/user/{uid}/.mutter-Xwaylandauth.*"))
    if matches:
        env["XAUTHORITY"] = matches[-1]
    return env


def set_text(text: str):
    if _is_wayland():
        subprocess.run(["wl-copy", "--", text], check=False)
    else:
        subprocess.run(["xclip", "-selection", "clipboard"],
                       input=text.encode(), check=False)


def _to_png(data: bytes) -> bytes:
    if data[:4] == b"\x89PNG":
        return data
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(data))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    except Exception as e:
        print(f"[clipd] 图片转PNG失败: {e}", flush=True)
        return data


def set_image(data: bytes):
    global _wl_copy_proc
    with _wl_copy_lock:
        proc = subprocess.Popen(["wl-copy", "--type", "image/png"], stdin=subprocess.PIPE)
        proc.stdin.write(data)
        proc.stdin.close()
        old = _wl_copy_proc
        _wl_copy_proc = proc
    if old and old.poll() is None:
        old.wait(timeout=1)

    # 同步写入 X11 剪贴板（供 XWayland 应用如飞书使用）
    try:
        env = _xclip_env()
        xp = subprocess.Popen(
            ["xclip", "-selection", "clipboard", "-t", "image/png"],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, env=env,
        )
        xp.stdin.write(data)
        xp.stdin.close()
    except FileNotFoundError:
        pass


def get_text() -> str | None:
    if _is_wayland():
        r = subprocess.run(["wl-paste", "--no-newline"], capture_output=True)
    else:
        r = subprocess.run(["xclip", "-selection", "clipboard", "-o"], capture_output=True)
    return r.stdout.decode("utf-8", errors="replace") if r.returncode == 0 else None


def get_image() -> bytes | None:
    if _is_wayland():
        r = subprocess.run(["wl-paste", "--list-types"], capture_output=True)
        if r.returncode != 0 or b"image" not in r.stdout:
            return None
        r2 = subprocess.run(["wl-paste", "--type", "image/png"], capture_output=True)
        return r2.stdout if r2.returncode == 0 else None
    else:
        r = subprocess.run(
            ["xclip", "-selection", "clipboard", "-t", "image/png", "-o"],
            capture_output=True,
        )
        return r.stdout if r.returncode == 0 and r.stdout else None


# ── mDNS 广播（avahi）────────────────────────────────────────────────────────

def advertise_mdns():
    try:
        proc = subprocess.Popen(
            ["avahi-publish-service", "clipd", "_clipd._tcp", str(UBUNTU_PORT)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        print(f"[clipd] mDNS 广播: clipd._clipd._tcp.local :{UBUNTU_PORT}")
        return proc
    except FileNotFoundError:
        print("[clipd] avahi-publish-service 未安装，跳过 mDNS（sudo apt install avahi-utils）")
        return None


# ── UDP 自动发现 ──────────────────────────────────────────────────────────────

def get_iface_broadcasts() -> list[str]:
    """从 `ip addr` 取所有接口的广播地址（兼容无 netifaces 环境）"""
    result = []
    try:
        import re
        out = subprocess.check_output(["ip", "addr"], text=True, stderr=subprocess.DEVNULL)
        result = re.findall(r'brd (\d+\.\d+\.\d+\.\d+)', out)
    except Exception:
        pass
    return list(set(result))


def broadcast_presence():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    msg = f"CLIPD_SERVER:{UBUNTU_PORT}".encode()
    print(f"[clipd] 广播发现中... (UDP :{DISCOVERY_PORT})")
    while True:
        targets = ["255.255.255.255"] + get_iface_broadcasts()
        for addr in targets:
            try:
                sock.sendto(msg, (addr, DISCOVERY_PORT))
            except Exception:
                pass
        time.sleep(BROADCAST_INTERVAL)


def listen_for_android():
    global _android_ip
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", DISCOVERY_PORT))
    while True:
        try:
            data, addr = sock.recvfrom(256)
            msg = data.decode()
            if msg.startswith("CLIPD_CLIENT:"):
                ip = addr[0]
                with _android_lock:
                    if _android_ip != ip:
                        _android_ip = ip
                        _save_last_android_ip(ip)
                        _add_log(f"Android 已配对: {ip}")
                        notify(f"Android 已连接: {ip}")
                        update_tray(True, f"已连接 Android: {ip}")
        except Exception:
            pass


def scan_for_android():
    """跨网段发现 Android：先查 ARP 邻居表，再补充常见私有网段扫描"""
    import concurrent.futures, re

    found = threading.Event()

    def _set_android(ip):
        global _android_ip
        with _android_lock:
            if _android_ip != ip:
                _android_ip = ip
                _save_last_android_ip(ip)
                print(f"[clipd] 发现 Android: {ip}", flush=True)
                notify(f"Android 已连接: {ip}")
                update_tray(True, f"已连接 Android: {ip}")
                # 反向通知手机：告知 Ubuntu IP，解决手机→Ubuntu 不可达时的发现问题
                threading.Thread(target=_notify_android_of_ubuntu, args=(ip,), daemon=True).start()

    def _notify_android_of_ubuntu(android_ip):
        my_ip = get_lan_ip()
        try:
            body = urllib.parse.urlencode({"ip": my_ip, "port": str(UBUNTU_PORT)}).encode()
            req = urllib.request.Request(
                f"http://{android_ip}:{ANDROID_PORT}/discover", data=body,
                headers={"Content-Type": "application/x-www-form-urlencoded"})
            urllib.request.urlopen(req, timeout=3)
            print(f"[clipd] 已通知 Android: Ubuntu @ {my_ip}", flush=True)
        except Exception as e:
            print(f"[clipd] 反向通知失败: {e}", flush=True)

    def is_clipd_android(ip):
        try:
            req = urllib.request.Request(f"http://{ip}:{ANDROID_PORT}/ping")
            with urllib.request.urlopen(req, timeout=0.5) as r:
                return r.read(16).strip() == b"CLIPD_OK"
        except Exception:
            return False

    def probe(ip):
        if found.is_set():
            return
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.35)
            s.connect((ip, ANDROID_PORT))
            s.close()
        except Exception:
            return
        if not found.is_set() and is_clipd_android(ip):
            found.set()
            _set_android(ip)

    # ① ARP 邻居表：覆盖所有已知网段，通常 < 200 条
    def get_arp_ips():
        try:
            out = subprocess.check_output(["ip", "neigh"], text=True, stderr=subprocess.DEVNULL)
            return [line.split()[0] for line in out.splitlines()
                    if line.split() and not line.split()[0].startswith("fe80")]
        except Exception:
            return []

    arp_ips = get_arp_ips()
    print(f"[clipd] ARP 发现 {len(arp_ips)} 个邻居，探测中...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        list(pool.map(probe, arp_ips))

    if found.is_set():
        return

    # ② 补充扫描：本机 /24 same-subnet（ARP 可能覆盖不全）
    # 跨子网场景请使用 QR 扫码配对 + state 持久化，避免扫描数万候选 IP 导致高 CPU
    my_ip = get_lan_ip()
    parts = my_ip.split(".")
    if len(parts) != 4:
        return
    a, b, my_c = parts[0], parts[1], parts[2]
    candidates = [f"{a}.{b}.{my_c}.{h}" for h in range(1, 255)]

    print(f"[clipd] 子网扫描 {len(candidates)} 个候选...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        list(pool.map(probe, candidates))


# ── 发送到 Android ────────────────────────────────────────────────────────────

def send_to_android(data: bytes | str, is_image: bool = False):
    with _android_lock:
        ip = _android_ip
    if not ip:
        return
    url = f"http://{ip}:{ANDROID_PORT}/clipboard"
    try:
        if is_image:
            assert isinstance(data, bytes)
            body = (
                b"--clipdboundary\r\n"
                b'Content-Disposition: form-data; name="image"; filename="clip.png"\r\n'
                b"Content-Type: image/png\r\n\r\n"
                + data
                + b"\r\n--clipdboundary--\r\n"
            )
            req = urllib.request.Request(url, data=body, headers={
                "Content-Type": "multipart/form-data; boundary=clipdboundary"
            })
        else:
            body = urllib.parse.urlencode({"text": data}).encode()
            req = urllib.request.Request(url, data=body, headers={
                "Content-Type": "application/x-www-form-urlencoded"
            })
        urllib.request.urlopen(req, timeout=1.5)
    except Exception:
        pass


# ── HTTP 服务器（接收来自 Android）────────────────────────────────────────────

class ClipHandler(http.server.BaseHTTPRequestHandler):
    def _learn_android_from_request(self, allow_learn_new=False):
        """从 client_address 刷新心跳时间戳。
        allow_learn_new=True 时（仅 GET /ping，由 Android 主动发现）可以学习新 IP；
        其他端点（POST /clipboard /file /notify）只更新已知 IP 的心跳，不学新 IP，
        防止局域网扫描器/桌面自身 curl 触发假连接。"""
        try:
            ip = self.client_address[0]
            if not ip or ip.startswith("127."):
                return
            # 排除桌面自己的所有本机 IP
            if ip in _local_ips():
                return
            global _android_ip, _android_last_seen, _android_state
            with _android_lock:
                # GET /ping 是 Android 的显式握手，允许替换已存在的 IP
                # （处理 DHCP 续约 / WiFi 漫游导致的 IP 变化）
                if _android_ip is None or (allow_learn_new and ip != _android_ip):
                    if not allow_learn_new:
                        return  # POST 不学新 IP，避免被 LAN 扫描器/陌生客户端激活
                    old_ip = _android_ip
                    _android_ip = ip
                    _save_last_android_ip(ip)
                    _android_last_seen = time.time()
                    _android_state = "connected"
                    if old_ip and old_ip != ip:
                        _add_log(f"Android IP 变更: {old_ip} → {ip}")
                    else:
                        _add_log(f"Android 已连接: {ip}")
                    try:
                        notify(f"Android 已连接: {ip}")
                        update_tray(True, f"已连接 Android: {ip}")
                    except Exception:
                        pass
                    return
                if ip != _android_ip:
                    return  # POST 来自陌生客户端，忽略
                _android_last_seen = time.time()
                prev_state = _android_state
                _android_state = "connected"
                if prev_state != "connected":
                    _add_log(f"Android 心跳恢复: {ip}")
                    try:
                        update_tray(True, f"已连接 Android: {ip}")
                    except Exception:
                        pass
        except Exception:
            pass

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/disconnect":
            self._handle_disconnect()
            return
        self._learn_android_from_request()
        if path == "/notify":
            self._handle_notify()
        elif path == "/file":
            self._handle_file()
        else:
            self._handle_clipboard()

    def _handle_disconnect(self):
        """Android 主动通知断开（停止同步），桌面立即清状态。
        鉴权：仅接受当前已配对的 Android IP，拒绝本机/陌生客户端，
        防止 LAN 上任意进程或恶意页面强制下线。"""
        global _android_ip, _android_last_seen, _android_state
        ip = self.client_address[0] if self.client_address else None
        if not ip or ip.startswith("127.") or ip in _local_ips():
            self.send_response(403); self.end_headers(); return
        with _android_lock:
            current = _android_ip
            if current is None or ip != current:
                self.send_response(403); self.end_headers(); return
            _android_ip = None
            _android_last_seen = 0.0
            _android_state = "offline"
        _add_log(f"Android 主动断开: {ip}")
        try:
            notify(f"Android 已停止同步")
            update_tray(False, "等待 Android 连接...")
        except Exception:
            pass
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def _handle_file(self):
        """接收 Android 上传的任意文件（流式写入）。
        协议：POST /file, Content-Length: N, X-Filename: <urlencoded utf-8 原始名>，
        body 即文件原始字节。"""
        try:
            length = int(self.headers.get("Content-Length", 0))
            if length <= 0:
                self.send_response(411); self.end_headers(); return
            raw_name = self.headers.get("X-Filename", "clipd_file")
            filename = urllib.parse.unquote(raw_name, errors="replace")
            filename = os.path.basename(filename).strip().replace("/", "_") or "clipd_file"
            dest_dir = os.path.expanduser("~/Downloads/clipd")
            os.makedirs(dest_dir, exist_ok=True)
            base, ext = os.path.splitext(filename)
            dest = os.path.join(dest_dir, filename)
            i = 1
            while os.path.exists(dest):
                dest = os.path.join(dest_dir, f"{base} ({i}){ext}")
                i += 1
            remaining = length
            with open(dest, "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(1 << 16, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
            size_kb = length // 1024
            print(f"[clipd] ← Android: 文件 {filename} ({size_kb}KB) → {dest}", flush=True)
            _add_log(f"← Android: 文件 {filename} {size_kb}KB")
            _stats["recv"] += 1
            try:
                _send_notification("文件已接收 ← Android",
                                   f"{filename}\n{dest}",
                                   icon="document-save", expire_time=6000,
                                   open_dir=dest_dir)
            except Exception:
                pass
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"ok")
        except Exception as e:
            _add_log(f"文件接收失败: {e}")
            try:
                self.send_response(500); self.end_headers()
                self.wfile.write(str(e).encode("utf-8", errors="replace"))
            except Exception:
                pass

    def _handle_notify(self):
        import json
        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)
        try:
            data     = json.loads(body.decode("utf-8"))
            app_name = data.get("appName", "Android")
            title    = data.get("title", "")
            text     = data.get("text", "")
            summary = f"{app_name} · {title}" if title else app_name
            body_text = text if text else ""
            if not body_text and title and title != app_name:
                body_text = title
            code = _extract_code(text or title)
            _send_notification(summary, body_text, icon="notification-message-im",
                               expire_time=6000, copy_text=code)
            _stats["notif"] += 1
            _add_log(f"← 通知: {app_name} / {title}")
        except Exception as e:
            _add_log(f"通知处理失败: {e}")
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def _handle_clipboard(self):
        global _last_hash
        ct     = self.headers.get("Content-Type", "")
        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)

        if "multipart" in ct:
            form = cgi.FieldStorage(
                fp=io.BytesIO(body),
                environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": ct,
                         "CONTENT_LENGTH": str(length)},
                keep_blank_values=True,
            )
            if "image" in form:
                img = form["image"].file.read()
                png = _to_png(img)
                print(f"[clipd] ← Android: 图片 {len(img)//1024}KB → PNG {len(png)//1024}KB",
                      flush=True)
                with _lock:
                    _last_hash = md5(png)
                try:
                    set_image(png)
                    notify("截图已同步 ← Android", png)
                    _stats["recv"] += 1
                    _add_log(f"← Android: 图片 {len(img)//1024}KB")
                except Exception as e:
                    _add_log(f"wl-copy 失败: {e}")
        else:
            params = urllib.parse.parse_qs(body.decode("utf-8", errors="replace"))
            text   = params.get("text", [""])[0]
            if text:
                h = md5(text)
                with _lock:
                    _last_hash = h
                set_text(text)
                notify(f"文字已同步 ← Android: {text[:40]}")
                _stats["recv"] += 1
                _add_log(f"← Android: 文字 {repr(text[:40])}")

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_GET(self):
        if self.path == "/ping":
            self._learn_android_from_request(allow_learn_new=True)
            body = b"CLIPD_OK"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(501)
            self.end_headers()

    def log_message(self, *_):
        pass


# ── 监听本机剪切板并推送 ───────────────────────────────────────────────────────

_push_executor = None  # 初始化见主入口


def _do_push():
    global _last_hash
    time.sleep(0.15)  # 等 clipboard owner 完成 Wayland data transfer 设置
    try:
        img = get_image()
        if img:
            h = md5(img)
            with _lock:
                if h == _last_hash:
                    return
                _last_hash = h
            send_to_android(img, is_image=True)
            _stats["sent"] += 1
            _add_log(f"→ Android: 图片 ({len(img)//1024}KB)")
            return
        text = get_text()
        if text:
            h = md5(text)
            with _lock:
                if h == _last_hash:
                    return
                _last_hash = h
            send_to_android(text)
            _stats["sent"] += 1
            _add_log(f"→ Android: 文字 {repr(text[:40])}")
    except Exception as e:
        print(f"[clipd] push error: {e}", flush=True)


def watch_wayland_clipboard():
    """Wayland 原生剪贴板监听。
    需要 compositor 支持 wlr-data-control 协议（sway/Hyprland 等）。
    GNOME Mutter / KDE 不支持，会自动放弃；XWayland 路径仍由 watch_and_push 兜底。"""
    import shutil
    if not shutil.which("wl-paste"):
        print("[clipd] 未安装 wl-clipboard，Wayland 监听跳过", flush=True)
        return
    # 探测协议支持
    try:
        probe = subprocess.run(
            ["wl-paste", "--watch", "true"],
            capture_output=True, text=True, timeout=2,
        )
        err = (probe.stderr or "") + (probe.stdout or "")
        if "wlroots data-control" in err or "wlr-data-control" in err:
            print("[clipd] compositor 不支持 wlr-data-control 协议（GNOME/KDE），"
                  "Wayland 原生监听不可用，仅 XWayland 路径生效", flush=True)
            return
    except subprocess.TimeoutExpired:
        pass  # 协议支持，--watch 会一直阻塞，超时是预期
    except Exception as e:
        print(f"[clipd] wl-paste 探测失败: {e}", flush=True)
        return

    print("[clipd] Wayland 剪贴板监听已启动 (wl-paste --watch)", flush=True)
    while True:
        try:
            proc = subprocess.Popen(
                ["wl-paste", "--watch", "sh", "-c", "echo CLIP_CHANGED"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            for line in proc.stdout:
                if "CLIP_CHANGED" in line and _push_executor is not None:
                    _push_executor.submit(_do_push)
            err = proc.stderr.read() if proc.stderr else ""
            if err:
                print(f"[clipd] wl-paste --watch 退出: {err.strip()[:200]}", flush=True)
            else:
                print("[clipd] wl-paste --watch 退出，5 秒后重启", flush=True)
        except Exception as e:
            print(f"[clipd] wl-paste --watch 异常: {e}, 5 秒后重启", flush=True)
        time.sleep(5)


def watch_and_push():
    """X11 XFixes SelectionNotify 事件驱动监听，不读内容、不干扰输入法"""
    import ctypes, ctypes.util

    env = _xclip_env()
    for k, v in env.items():
        os.environ[k] = v

    x11 = ctypes.cdll.LoadLibrary(ctypes.util.find_library('X11'))
    xfixes = ctypes.cdll.LoadLibrary(ctypes.util.find_library('Xfixes'))

    x11.XInitThreads()

    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XDefaultRootWindow.restype = ctypes.c_ulong
    x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
    x11.XInternAtom.restype = ctypes.c_ulong
    x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    x11.XNextEvent.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    xfixes.XFixesSelectSelectionInput.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]

    display = x11.XOpenDisplay(None)
    if not display:
        print("[clipd] X11 display 打开失败，剪切板监听未启动", flush=True)
        return

    root = x11.XDefaultRootWindow(display)
    clipboard_atom = x11.XInternAtom(display, b"CLIPBOARD", False)

    # XFixesSelectSelectionInput(display, window, selection, mask)
    XFixesSetSelectionOwnerNotifyMask = 1
    XFixesSelectionWindowDestroyNotifyMask = 2
    XFixesSelectionClientCloseNotifyMask = 4
    mask = (XFixesSetSelectionOwnerNotifyMask |
            XFixesSelectionWindowDestroyNotifyMask |
            XFixesSelectionClientCloseNotifyMask)

    xfixes.XFixesQueryExtension.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int)]
    xfixes.XFixesQueryExtension.restype = ctypes.c_int
    event_base = ctypes.c_int()
    error_base = ctypes.c_int()
    xfixes.XFixesQueryExtension(display, ctypes.byref(event_base), ctypes.byref(error_base))

    xfixes.XFixesSelectSelectionInput(display, root, clipboard_atom, mask)

    # 初始化 hash
    global _last_hash
    text = get_text()
    if text and not _last_hash:
        with _lock:
            _last_hash = md5(text)

    print(f"[clipd] X11 XFixes 剪切板监听已启动（事件驱动）", flush=True)

    # XEvent 结构体大小 = 192 字节
    event_buf = ctypes.create_string_buffer(192)
    last_submit_ts = [0.0]
    DEBOUNCE_SEC = 0.25
    while True:
        x11.XNextEvent(display, event_buf)
        # 去抖：同一次复制 XFixes 可能连发多个事件（ownership / destroy / close），
        # 250ms 内只提交一次 push 任务，避免 wl-paste 子进程风暴
        now = time.time()
        if now - last_submit_ts[0] < DEBOUNCE_SEC:
            continue
        last_submit_ts[0] = now
        _push_executor.submit(_do_push)


def _watchdog_android():
    """两级心跳超时：
       SUSPECT (>60s)  → tray 显示心跳延迟，但 IP 保留可用于发送
       OFFLINE (>300s) → 清空 IP、广播离线
    避免 Doze / WiFi 省电导致的 UI 闪烁。"""
    global _android_ip, _android_last_seen, _android_state
    while True:
        time.sleep(10)
        try:
            with _android_lock:
                ip = _android_ip
                last = _android_last_seen
                state = _android_state
            if not ip or not last:
                continue
            elapsed = time.time() - last
            if elapsed > ANDROID_HARD_OFFLINE_SEC and state != "offline":
                with _android_lock:
                    if _android_ip == ip:
                        _android_ip = None
                        _android_last_seen = 0.0
                        _android_state = "offline"
                _add_log(f"Android 离线: {ip} (无心跳 {int(elapsed)}s)")
                try:
                    notify(f"Android 已断开: {ip}")
                    update_tray(False, "等待 Android 连接...")
                except Exception:
                    pass
            elif elapsed > ANDROID_SUSPECT_SEC and state == "connected":
                with _android_lock:
                    _android_state = "suspect"
                _add_log(f"Android 心跳延迟: {ip} ({int(elapsed)}s)")
                try:
                    update_tray(True, f"心跳延迟 {int(elapsed)}s: {ip}")
                except Exception:
                    pass
        except Exception:
            pass


# ── 主入口 ────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print(f"[clipd] v{APP_VERSION} 启动", flush=True)
    _stats["start_time"] = time.time()
    create_tray()

    if len(sys.argv) >= 2:
        _android_ip = sys.argv[1]
        _add_log(f"启动（手动指定 Android: {_android_ip}）")
        update_tray(True, f"已连接 Android: {_android_ip}")
    else:
        _add_log("启动（自动发现模式）")
        # 尝试恢复上次的 Android IP，避免冷启动扫描 5 万个候选 IP
        last_ip = _load_last_android_ip()
        if last_ip:
            def _verify_last():
                try:
                    import urllib.request as _ur
                    req = _ur.Request(f"http://{last_ip}:{ANDROID_PORT}/ping")
                    with _ur.urlopen(req, timeout=1.5) as r:
                        if r.read(16).strip() == b"CLIPD_OK":
                            global _android_ip
                            with _android_lock:
                                _android_ip = last_ip
                                global _android_last_seen, _android_state
                                _android_last_seen = time.time()
                                _android_state = "connected"
                            _add_log(f"恢复上次 Android: {last_ip}")
                            update_tray(True, f"已连接 Android: {last_ip}")
                except Exception:
                    pass
            threading.Thread(target=_verify_last, daemon=True).start()
        advertise_mdns()
        threading.Thread(target=broadcast_presence, daemon=True).start()
        threading.Thread(target=listen_for_android, daemon=True).start()
        def _delayed_scan():
            time.sleep(15)
            with _android_lock:
                already = _android_ip
            if not already:
                threading.Thread(target=scan_for_android, daemon=True).start()
        threading.Thread(target=_delayed_scan, daemon=True).start()
        threading.Thread(target=_watchdog_android, daemon=True).start()

    lan_ip = get_lan_ip()
    _add_log(f"本机 IP: {lan_ip}:{UBUNTU_PORT}")

    import concurrent.futures
    _push_executor = concurrent.futures.ThreadPoolExecutor(
        max_workers=4, thread_name_prefix="clipd-push"
    )
    threading.Thread(target=watch_and_push, daemon=True).start()
    if _is_wayland():
        threading.Thread(target=watch_wayland_clipboard, daemon=True).start()

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", UBUNTU_PORT), ClipHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n[clipd] 已停止")
