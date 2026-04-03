#!/usr/bin/env bash
# clipd Ubuntu 端安装脚本
# 用法: ./install.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="$HOME/.local/share/clipd"
BIN_DIR="$HOME/.local/bin"

echo "=== clipd 安装 ==="

# 1. 依赖
echo "安装依赖..."
sudo apt-get install -y wl-clipboard xclip python3-pil libnotify-bin avahi-utils 2>/dev/null | grep -E "已安装|newly installed|升级" || true

# 2. 安装文件
mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp "$SCRIPT_DIR/ubuntu_clipd.py" "$INSTALL_DIR/clipd.py"
chmod +x "$INSTALL_DIR/clipd.py"

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
Description=clipd clipboard sync
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

# 4. .desktop 入口（出现在应用菜单）
mkdir -p "$HOME/.local/share/applications"
cat > "$HOME/.local/share/applications/clipd.desktop" << EOF
[Desktop Entry]
Name=clipd
Comment=Android ↔ Ubuntu 剪贴板同步
Exec=$BIN_DIR/clipd
Icon=edit-paste
Terminal=false
Type=Application
Categories=Utility;
Keywords=clipboard;sync;android;
EOF

echo ""
echo "✓ 安装完成"
echo "  服务状态: systemctl --user status clipd"
echo "  查看日志: journalctl --user -u clipd -f"
echo "  卸载:     systemctl --user disable --now clipd && rm -rf $INSTALL_DIR $BIN_DIR/clipd $HOME/.local/share/applications/clipd.desktop"
