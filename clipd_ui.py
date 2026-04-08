#!/usr/bin/env python3
"""
Clipd 管理界面 — 独立 GTK 应用，管理 clipd.service 并显示实时状态/日志
"""

import os
import re
import subprocess
import threading
import time

import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, GLib, Gdk, Gio

SERVICE_NAME = "clipd.service"
UBUNTU_PORT  = 8888
ANDROID_PORT = 8889


# ── systemctl helpers ────────────────────────────────────────────────────────

def _systemctl(*args):
    try:
        r = subprocess.run(
            ["systemctl", "--user", *args],
            capture_output=True, text=True, timeout=10,
        )
        return r.returncode == 0, r.stdout.strip()
    except Exception as e:
        return False, str(e)


def svc_is_active():
    ok, out = _systemctl("is-active", SERVICE_NAME)
    return out == "active"


def svc_is_enabled():
    ok, out = _systemctl("is-enabled", SERVICE_NAME)
    return out == "enabled"


def svc_start():
    return _systemctl("start", SERVICE_NAME)


def svc_stop():
    return _systemctl("stop", SERVICE_NAME)


def svc_restart():
    return _systemctl("restart", SERVICE_NAME)


def svc_uptime():
    """返回服务运行时间（秒），未运行返回 None"""
    ok, out = _systemctl("show", SERVICE_NAME, "--property=ActiveEnterTimestamp")
    if not ok or "=" not in out:
        return None
    ts_str = out.split("=", 1)[1].strip()
    if not ts_str:
        return None
    try:
        from datetime import datetime
        # 格式: "Thu 2026-04-03 18:17:00 CST"
        # 用 systemd 的 monotonic 更可靠
        ok2, mono = _systemctl("show", SERVICE_NAME, "--property=ActiveEnterTimestampMonotonic")
        if ok2 and "=" in mono:
            usec = int(mono.split("=")[1])
            if usec == 0:
                return None
            # 获取系统当前 monotonic
            with open("/proc/uptime") as f:
                sys_uptime = float(f.read().split()[0])
            return max(0, sys_uptime - usec / 1_000_000)
    except Exception:
        pass
    return None


def svc_pid():
    ok, out = _systemctl("show", SERVICE_NAME, "--property=MainPID")
    if ok and "=" in out:
        pid = out.split("=")[1]
        return int(pid) if pid and pid != "0" else None
    return None


# ── 日志解析 ─────────────────────────────────────────────────────────────────

def parse_log_stats(lines: list[str]) -> dict:
    """从日志行中统计 sent/recv/notif"""
    sent = recv = notif = 0
    android_ip = None
    for line in lines:
        if "→ Android:" in line:
            sent += 1
        elif "← Android:" in line:
            recv += 1
        elif "← 通知:" in line:
            notif += 1
        # 匹配连接 IP
        m = re.search(r'Android 已[配连].*?(\d+\.\d+\.\d+\.\d+)', line)
        if m:
            android_ip = m.group(1)
        m2 = re.search(r'Android 反向发现: (\d+\.\d+\.\d+\.\d+)', line)
        if m2:
            android_ip = m2.group(1)
    return {"sent": sent, "recv": recv, "notif": notif, "android_ip": android_ip}


# ── 获取本机 IP ──────────────────────────────────────────────────────────────

def get_lan_ip():
    try:
        out = subprocess.check_output(["ip", "-o", "-4", "addr"], text=True, stderr=subprocess.DEVNULL)
        skip = ("lo", "docker", "br-", "veth", "lxc", "tailscale0", "Mihomo", "tun", "wg")
        for line in out.splitlines():
            parts = line.split()
            if len(parts) < 4:
                continue
            iface = parts[1]
            if any(iface.startswith(s) for s in skip):
                continue
            ip = parts[3].split("/")[0]
            if ip.startswith("192.168.") or ip.startswith("10."):
                return ip
    except Exception:
        pass
    return "?"


# ── GTK 窗口 ─────────────────────────────────────────────────────────────────

class ClipdWindow(Gtk.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="Clipd")
        self.set_default_size(520, 580)
        self.set_icon_name("edit-paste")

        self._journal_proc = None
        self._log_lines = []
        self._stats = {"sent": 0, "recv": 0, "notif": 0, "android_ip": None}

        self._apply_css()
        self._build_ui()
        self._start_journal_tail()

        # 定时刷新状态（1s）
        GLib.timeout_add(3000, self._refresh)
        # 首次立即刷新
        GLib.idle_add(self._refresh)

    def _apply_css(self):
        css = b"""
        .status-active   { color: #2ecc71; font-weight: bold; font-size: 15px; }
        .status-inactive { color: #e74c3c; font-weight: bold; font-size: 15px; }
        .status-waiting  { color: #e67e22; font-weight: bold; font-size: 15px; }
        .stat-value      { font-size: 22px; font-weight: bold; }
        .stat-label      { font-size: 11px; color: #888; }
        .info-key        { font-size: 12px; color: #999; }
        .info-val        { font-size: 12px; }
        .log-view        { font-family: monospace; font-size: 11px; }
        .svc-btn         { min-width: 72px; }
        """
        provider = Gtk.CssProvider()
        provider.load_from_data(css)
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(), provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

    def _build_ui(self):
        vbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        vbox.set_margin_top(14)
        vbox.set_margin_bottom(12)
        vbox.set_margin_start(16)
        vbox.set_margin_end(16)
        self.add(vbox)

        # ── 服务状态 + 控制 ──
        svc_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)

        self.svc_dot = Gtk.Label(label="●")
        self.svc_label = Gtk.Label(label="检查中...")
        svc_box.pack_start(self.svc_dot, False, False, 0)
        svc_box.pack_start(self.svc_label, False, False, 0)

        self.btn_stop = Gtk.Button(label="停止")
        self.btn_stop.get_style_context().add_class("svc-btn")
        self.btn_stop.connect("clicked", self._on_stop)

        self.btn_start = Gtk.Button(label="启动")
        self.btn_start.get_style_context().add_class("svc-btn")
        self.btn_start.connect("clicked", self._on_start)

        self.btn_restart = Gtk.Button(label="重启")
        self.btn_restart.get_style_context().add_class("svc-btn")
        self.btn_restart.connect("clicked", self._on_restart)

        self.btn_qr = Gtk.Button(label="配对码")
        self.btn_qr.get_style_context().add_class("svc-btn")
        self.btn_qr.connect("clicked", self._on_show_qr)

        svc_box.pack_end(self.btn_stop, False, False, 0)
        svc_box.pack_end(self.btn_restart, False, False, 0)
        svc_box.pack_end(self.btn_start, False, False, 0)
        svc_box.pack_end(self.btn_qr, False, False, 0)
        vbox.pack_start(svc_box, False, False, 0)

        # ── 开机自启 ──
        auto_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        auto_label = Gtk.Label(label="开机自启")
        auto_label.get_style_context().add_class("info-key")
        self.auto_switch = Gtk.Switch()
        self.auto_switch.set_active(svc_is_enabled())
        self.auto_switch.connect("state-set", self._on_autostart_toggle)
        auto_box.pack_start(auto_label, False, False, 0)
        auto_box.pack_start(self.auto_switch, False, False, 0)
        vbox.pack_start(auto_box, False, False, 0)

        sep1 = Gtk.Separator()
        vbox.pack_start(sep1, False, False, 2)

        # ── 信息区 ──
        info_grid = Gtk.Grid(column_spacing=12, row_spacing=4)
        self.info_ip      = self._info_row(info_grid, 0, "本机 IP")
        self.info_android = self._info_row(info_grid, 1, "Android")
        self.info_uptime  = self._info_row(info_grid, 2, "运行时间")
        self.info_pid     = self._info_row(info_grid, 3, "PID")
        vbox.pack_start(info_grid, False, False, 0)

        # ── 统计卡片 ──
        stat_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=0, homogeneous=True)
        stat_box.set_margin_top(6)
        _, self.val_sent  = self._stat_card(stat_box, "发送")
        _, self.val_recv  = self._stat_card(stat_box, "接收")
        _, self.val_notif = self._stat_card(stat_box, "通知")
        vbox.pack_start(stat_box, False, False, 0)

        sep2 = Gtk.Separator()
        sep2.set_margin_top(4)
        vbox.pack_start(sep2, False, False, 0)

        # ── 日志区 ──
        log_header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        log_title = Gtk.Label(label="日志", xalign=0)
        log_title.get_style_context().add_class("stat-label")
        log_header.pack_start(log_title, True, True, 0)

        btn_clear = Gtk.Button(label="清空")
        btn_clear.connect("clicked", self._on_clear_log)
        log_header.pack_end(btn_clear, False, False, 0)
        vbox.pack_start(log_header, False, False, 0)

        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        scroll.set_vexpand(True)

        self.log_view = Gtk.TextView()
        self.log_view.set_editable(False)
        self.log_view.set_cursor_visible(False)
        self.log_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self.log_view.get_style_context().add_class("log-view")
        self.log_buf = self.log_view.get_buffer()
        scroll.add(self.log_view)
        vbox.pack_start(scroll, True, True, 0)

    def _info_row(self, grid, row, key):
        k = Gtk.Label(label=key, xalign=0)
        k.get_style_context().add_class("info-key")
        v = Gtk.Label(label="—", xalign=0, selectable=True)
        v.get_style_context().add_class("info-val")
        grid.attach(k, 0, row, 1, 1)
        grid.attach(v, 1, row, 1, 1)
        return v

    def _stat_card(self, parent, label_text):
        frame = Gtk.Frame()
        frame.set_shadow_type(Gtk.ShadowType.IN)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        val = Gtk.Label(label="0")
        val.get_style_context().add_class("stat-value")
        lbl = Gtk.Label(label=label_text)
        lbl.get_style_context().add_class("stat-label")
        box.pack_start(val, False, False, 0)
        box.pack_start(lbl, False, False, 0)
        frame.add(box)
        parent.pack_start(frame, True, True, 2)
        return frame, val

    # ── 服务控制回调 ──

    def _svc_action(self, action_fn, action_name):
        """在后台线程执行 systemctl 操作"""
        def _do():
            ok, msg = action_fn()
            GLib.idle_add(self._refresh)
            if not ok:
                GLib.idle_add(self._show_error, f"{action_name}失败: {msg}")
        threading.Thread(target=_do, daemon=True).start()

    def _on_start(self, btn):
        self._svc_action(svc_start, "启动")

    def _on_stop(self, btn):
        self._svc_action(svc_stop, "停止")

    def _on_restart(self, btn):
        self._svc_action(svc_restart, "重启")
        # 重启后重新 tail 日志
        GLib.timeout_add(1500, self._start_journal_tail)

    def _on_autostart_toggle(self, switch, state):
        action = "enable" if state else "disable"
        threading.Thread(
            target=lambda: _systemctl(action, SERVICE_NAME),
            daemon=True,
        ).start()

    def _on_show_qr(self, btn):
        try:
            import qrcode, tempfile
            ip = get_lan_ip()
            content = f"clipd://connect?ip={ip}&port={UBUNTU_PORT}"
            img = qrcode.make(content)
            tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False, prefix="clipd_qr_")
            img.save(tmp.name)
            tmp.close()

            dlg = Gtk.Dialog(title=f"配对码 — {ip}:{UBUNTU_PORT}",
                             transient_for=self, modal=True)
            dlg.add_button("关闭", Gtk.ResponseType.CLOSE)
            box = dlg.get_content_area()
            box.set_spacing(8)
            box.set_margin_top(12); box.set_margin_bottom(12)
            box.set_margin_start(12); box.set_margin_end(12)
            image = Gtk.Image.new_from_file(tmp.name)
            box.pack_start(image, True, True, 0)
            tip = Gtk.Label(label="在手机 clipd 点右上角 ⌖ 扫码配对")
            box.pack_start(tip, False, False, 0)
            dlg.show_all()
            dlg.run()
            dlg.destroy()
            try: os.unlink(tmp.name)
            except Exception: pass
        except ImportError:
            self._show_error("需要安装 qrcode: pip3 install 'qrcode[pil]'")
        except Exception as e:
            self._show_error(f"生成二维码失败: {e}")

    def _on_clear_log(self, btn):
        self._log_lines.clear()
        # 仅清统计计数，不清 android_ip（保持当前连接状态显示）
        self._stats["sent"] = 0
        self._stats["recv"] = 0
        self._stats["notif"] = 0
        self.log_buf.set_text("")
        # 重启 journal tail 并丢弃历史，避免管道里残留的旧行清空后再次涌出
        GLib.idle_add(self._start_journal_tail, True)

    def _show_error(self, msg):
        dlg = Gtk.MessageDialog(
            transient_for=self, modal=True,
            message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.OK,
            text=msg,
        )
        dlg.run()
        dlg.destroy()

    # ── 状态样式 ──

    def _set_status(self, text, css_class):
        for cls in ("status-active", "status-inactive", "status-waiting"):
            self.svc_dot.get_style_context().remove_class(cls)
            self.svc_label.get_style_context().remove_class(cls)
        self.svc_dot.get_style_context().add_class(css_class)
        self.svc_label.get_style_context().add_class(css_class)
        self.svc_label.set_text(text)

    # ── 定时刷新 ──

    def _refresh(self):
        active = svc_is_active()

        # 服务状态
        if active:
            android_ip = self._stats.get("android_ip")
            if android_ip:
                self._set_status(f"运行中 · 已连接 {android_ip}", "status-active")
            else:
                self._set_status("运行中 · 等待连接", "status-waiting")
            self.btn_start.set_sensitive(False)
            self.btn_stop.set_sensitive(True)
            self.btn_restart.set_sensitive(True)
        else:
            self._set_status("已停止", "status-inactive")
            self.btn_start.set_sensitive(True)
            self.btn_stop.set_sensitive(False)
            self.btn_restart.set_sensitive(False)

        # 信息
        self.info_ip.set_text(f"{get_lan_ip()}:{UBUNTU_PORT}")
        self.info_android.set_text(
            f"{self._stats['android_ip']}:{ANDROID_PORT}"
            if self._stats.get("android_ip") else "未连接"
        )

        pid = svc_pid()
        self.info_pid.set_text(str(pid) if pid else "—")

        uptime = svc_uptime()
        if uptime is not None:
            h = int(uptime) // 3600
            m = (int(uptime) % 3600) // 60
            s = int(uptime) % 60
            self.info_uptime.set_text(f"{h}h {m}m {s}s" if h else f"{m}m {s}s")
        else:
            self.info_uptime.set_text("—")

        # 统计
        self.val_sent.set_text(str(self._stats["sent"]))
        self.val_recv.set_text(str(self._stats["recv"]))
        self.val_notif.set_text(str(self._stats["notif"]))

        return True  # 继续定时

    # ── journalctl tail ──

    def _start_journal_tail(self, fresh=False):
        # 杀掉旧进程
        if self._journal_proc and self._journal_proc.poll() is None:
            self._journal_proc.kill()

        n_arg = "0" if fresh else "200"
        try:
            self._journal_proc = subprocess.Popen(
                ["journalctl", "--user", "-u", SERVICE_NAME,
                 "-f", "-n", n_arg, "--no-hostname", "-o", "short-iso"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True,
            )
            threading.Thread(target=self._read_journal, daemon=True).start()
        except Exception as e:
            self._append_log(f"[UI] 无法读取日志: {e}")
        return False  # 不重复 GLib.timeout 回调

    def _read_journal(self):
        proc = self._journal_proc
        for line in proc.stdout:
            line = line.rstrip("\n")
            if not line:
                continue
            # 提取 [clipd] 之后的内容，或保留原文
            m = re.search(r'\[clipd\]\s*(.*)', line)
            display = m.group(1) if m else line
            # 提取时间戳
            ts_match = re.match(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})', line)
            ts = ts_match.group(1).split("T")[1] if ts_match else ""
            entry = f"[{ts}] {display}" if ts else display

            self._log_lines.append(entry)
            if len(self._log_lines) > 500:
                self._log_lines[:] = self._log_lines[-300:]

            # 更新统计
            if "→ Android:" in line:
                self._stats["sent"] += 1
            elif "← Android:" in line or "文字已同步" in line or "截图已同步" in line:
                self._stats["recv"] += 1
            elif "← 通知:" in line or "Android 通知:" in line:
                self._stats["notif"] += 1
            if "Android 离线" in line or "Android 已断开" in line:
                self._stats["android_ip"] = None
            else:
                ip_m = re.search(r'Android.*?(\d+\.\d+\.\d+\.\d+)', line)
                if ip_m and "反向通知失败" not in line:
                    self._stats["android_ip"] = ip_m.group(1)

            GLib.idle_add(self._schedule_log_refresh)

    def _update_log_view(self):
        # 最新的在最上面
        self.log_buf.set_text("\n".join(reversed(self._log_lines)))
        # 滚动到顶
        start = self.log_buf.get_start_iter()
        self.log_view.scroll_to_iter(start, 0, True, 0, 0)
        self._log_refresh_pending = False
        return False

    def _schedule_log_refresh(self):
        """去抖：300ms 内多次请求只刷新一次，避免 journal 高频写入时 GTK 卡顿。"""
        if getattr(self, '_log_refresh_pending', False):
            return
        self._log_refresh_pending = True
        GLib.timeout_add(300, self._update_log_view)

    def _append_log(self, msg):
        ts = time.strftime("%H:%M:%S")
        self._log_lines.append(f"[{ts}] {msg}")
        GLib.idle_add(self._schedule_log_refresh)

    def do_destroy(self):
        if self._journal_proc and self._journal_proc.poll() is None:
            self._journal_proc.kill()
        Gtk.ApplicationWindow.do_destroy(self)


class ClipdApp(Gtk.Application):
    def __init__(self):
        super().__init__(
            application_id="com.clipd.manager",
            flags=Gio.ApplicationFlags.FLAGS_NONE,
        )
        self._win = None

    def do_activate(self):
        if self._win:
            self._win.present()
        else:
            self._win = ClipdWindow(self)
            self._win.show_all()


if __name__ == "__main__":
    app = ClipdApp()
    app.run()
