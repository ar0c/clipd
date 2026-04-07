#!/usr/bin/env bash
# clipd Ubuntu 端安装脚本
# 用法: ./install.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="$HOME/.local/share/clipd"
BIN_DIR="$HOME/.local/bin"

echo "=== Clipd 桌面端 安装 ==="

# 1. 依赖
echo "安装依赖..."
sudo apt-get install -y wl-clipboard xclip python3-pil libnotify-bin avahi-utils 2>/dev/null | grep -E "已安装|newly installed|升级" || true

# 2. 安装文件
mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp "$SCRIPT_DIR/ubuntu_clipd.py" "$INSTALL_DIR/clipd.py"
cp "$SCRIPT_DIR/clipd_ui.py"    "$INSTALL_DIR/clipd_ui.py"
chmod +x "$INSTALL_DIR/clipd.py" "$INSTALL_DIR/clipd_ui.py"

# 图标
ICON_DIR_BASE="$HOME/.local/share/icons/hicolor"
for sz in 128x128 192x192; do
    mkdir -p "$ICON_DIR_BASE/$sz/apps"
done
# 优先用 assets/clipd.png（仓库内置），否则回退 Android 应用图标
if [ -f "$SCRIPT_DIR/assets/clipd.png" ]; then
    cp "$SCRIPT_DIR/assets/clipd.png" "$ICON_DIR_BASE/192x192/apps/clipd.png"
    cp "$SCRIPT_DIR/assets/clipd.png" "$ICON_DIR_BASE/128x128/apps/clipd.png"
elif [ -f "$SCRIPT_DIR/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" ]; then
    cp "$SCRIPT_DIR/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" "$ICON_DIR_BASE/192x192/apps/clipd.png"
    cp "$SCRIPT_DIR/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png"  "$ICON_DIR_BASE/128x128/apps/clipd.png"
fi
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -f -t "$ICON_DIR_BASE" 2>/dev/null || true

# 启动脚本
cat > "$BIN_DIR/clipd" << 'EOF'
#!/usr/bin/env bash
exec /usr/bin/python3 "$HOME/.local/share/clipd/clipd.py" "$@"
EOF
chmod +x "$BIN_DIR/clipd"

# 3. systemd 用户服务
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/clipd.service" << EOF
[Unit]
Description=Clipd Desktop clipboard sync
After=graphical-session.target

[Service]
ExecStart=/usr/bin/python3 $INSTALL_DIR/clipd.py
Restart=on-failure
RestartSec=3
Environment=WAYLAND_DISPLAY=wayland-0
Environment=XDG_RUNTIME_DIR=/run/user/%U
Environment=DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/%U/bus
Environment=DISPLAY=:0

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable clipd
systemctl --user restart clipd

# 4. .desktop 入口（文件名必须 = application_id，否则 GNOME dock 无法匹配 GApplication 窗口）
APP_DIR="$HOME/.local/share/applications"
mkdir -p "$APP_DIR"
# 清掉旧文件
rm -f "$APP_DIR/clipd.desktop"
sed "s|__INSTALL_DIR__|$INSTALL_DIR|g" "$SCRIPT_DIR/clipd.desktop" \
    > "$APP_DIR/com.clipd.manager.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$APP_DIR" 2>/dev/null || true

echo ""
echo "✓ 安装完成"
echo "  服务状态: systemctl --user status clipd"
echo "  查看日志: journalctl --user -u clipd -f"
echo "  卸载:     systemctl --user disable --now clipd && rm -rf $INSTALL_DIR $BIN_DIR/clipd $HOME/.local/share/applications/com.clipd.manager.desktop $ICON_DIR_BASE/128x128/apps/clipd.png $ICON_DIR_BASE/192x192/apps/clipd.png"
