#!/usr/bin/env python3
"""
clipd - Ubuntu 端剪切板同步守护进程
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

_last_hash     = ""
_lock          = threading.Lock()
_android_ip    = None
_android_lock  = threading.Lock()
_wl_copy_proc  = None
_wl_copy_lock  = threading.Lock()
_tray_icon     = None


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
    subprocess.Popen(
        ["notify-send", "clipd", msg, f"--icon={icon}", "--expire-time=4000"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    if cleanup:
        def _rm():
            time.sleep(6)
            try: os.unlink(cleanup)
            except: pass
        threading.Thread(target=_rm, daemon=True).start()


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
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("显示配对码", lambda icon, item: show_qr()),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("退出", lambda icon, item: (icon.stop(), os._exit(0))),
    )


def create_tray():
    global _tray_icon
    try:
        import pystray
        _tray_icon = pystray.Icon(
            "clipd",
            _make_tray_image(False),
            "clipd - 等待连接",
            _tray_menu("clipd - 等待连接"),
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
        status = f"clipd - {label}" if label else ("clipd - 已连接" if connected else "clipd - 等待连接")
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
                        print(f"[clipd] Android 已配对: {ip}")
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

    # ② 补充扫描：本机 /16 前缀 + 常见私有段
    my_ip = get_lan_ip()
    parts = my_ip.split(".")
    a, b, my_c = parts[0], parts[1], int(parts[2]) if len(parts) == 4 else (0,)
    thirds = sorted(set(range(0, 11)) | {my_c})
    candidates = [f"{a}.{b}.{c}.{h}" for c in thirds for h in range(1, 255)]
    # 常见私有段补充
    if a != "192":
        candidates += [f"192.168.{c}.{h}" for c in range(0, 21) for h in range(1, 255)]
    if a != "10":
        candidates += [f"10.0.{c}.{h}" for c in range(0, 6) for h in range(1, 255)]
    # 172.16-31 只扫 /24（不扫全段，避免过慢）
    if a != "172":
        candidates += [f"172.{b2}.{my_c}.{h}"
                       for b2 in range(16, 33) for h in range(1, 255)]

    print(f"[clipd] 子网扫描 {len(candidates)} 个候选...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
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
    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/notify":
            self._handle_notify()
        else:
            self._handle_clipboard()

    def _handle_notify(self):
        import json
        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)
        try:
            data     = json.loads(body.decode("utf-8"))
            app_name = data.get("appName", "Android")
            title    = data.get("title", "")
            text     = data.get("text", "")
            summary  = f"{title}: {text}" if title and text else (title or text)
            subprocess.Popen(
                ["notify-send", app_name, summary,
                 "--icon=notification-message-im", "--expire-time=6000"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            print(f"[clipd] ← Android 通知: {app_name} / {title}", flush=True)
        except Exception as e:
            print(f"[clipd] 通知处理失败: {e}", flush=True)
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
                    print("[clipd] wl-copy 成功", flush=True)
                except Exception as e:
                    print(f"[clipd] wl-copy 失败: {e}", flush=True)
        else:
            params = urllib.parse.parse_qs(body.decode("utf-8", errors="replace"))
            text   = params.get("text", [""])[0]
            if text:
                h = md5(text)
                with _lock:
                    _last_hash = h
                set_text(text)
                notify(f"文字已同步 ← Android: {text[:40]}")

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_GET(self):
        if self.path == "/ping":
            # Android 扫描时调 /ping，顺便让 Ubuntu 学到 Android 的 IP
            android_ip = self.client_address[0]
            global _android_ip
            with _android_lock:
                if _android_ip != android_ip:
                    _android_ip = android_ip
                    print(f"[clipd] Android 反向发现: {android_ip}", flush=True)
                    notify(f"Android 已连接: {android_ip}")
                    update_tray(True, f"已连接 Android: {android_ip}")
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
            print(f"[clipd] → Android: 图片 ({len(img)//1024}KB)", flush=True)
            return
        text = get_text()
        if text:
            h = md5(text)
            with _lock:
                if h == _last_hash:
                    return
                _last_hash = h
            send_to_android(text)
            print(f"[clipd] → Android: 文字 {repr(text[:40])}", flush=True)
    except Exception as e:
        print(f"[clipd] push error: {e}", flush=True)


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
    while True:
        x11.XNextEvent(display, event_buf)
        # 收到事件 = clipboard owner 改变，延迟读取
        time.sleep(0.15)
        _push_executor.submit(_do_push)


# ── 主入口 ────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    create_tray()

    if len(sys.argv) >= 2:
        _android_ip = sys.argv[1]
        print(f"[clipd] 启动（手动指定 Android: {_android_ip}）")
        update_tray(True, f"已连接 Android: {_android_ip}")
    else:
        print("[clipd] 启动（自动发现模式）")
        advertise_mdns()
        threading.Thread(target=broadcast_presence, daemon=True).start()
        threading.Thread(target=listen_for_android, daemon=True).start()
        # 15 秒后若仍未发现，启动跨网段 TCP 扫描
        def _delayed_scan():
            time.sleep(15)
            with _android_lock:
                already = _android_ip
            if not already:
                threading.Thread(target=scan_for_android, daemon=True).start()
        threading.Thread(target=_delayed_scan, daemon=True).start()

    lan_ip = get_lan_ip()
    print(f"  本机 IP:   {lan_ip}")
    print(f"  HTTP 监听: :{UBUNTU_PORT}")
    print(f"  UDP 发现:  :{DISCOVERY_PORT}")
    print(f"  mDNS:      clipd._clipd._tcp.local")
    print(f"  配对码:    clipd://connect?ip={lan_ip}&port={UBUNTU_PORT}")

    import concurrent.futures
    _push_executor = concurrent.futures.ThreadPoolExecutor(
        max_workers=4, thread_name_prefix="clipd-push"
    )
    threading.Thread(target=watch_and_push, daemon=True).start()

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", UBUNTU_PORT), ClipHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n[clipd] 已停止")
