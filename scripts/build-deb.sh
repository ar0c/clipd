#!/usr/bin/env bash
# 构建 clipd 桌面端 .deb 包
# 用法: ./scripts/build-deb.sh [version]
# 输出: artifacts/clipd_<version>_all.deb

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
VERSION="${1:-$(grep -Po 'versionName = "\K[^"]+' "$ROOT/android/app/build.gradle.kts" || echo 1.0.0)}"
PKG="clipd_${VERSION}_all"
STAGE="$ROOT/build/$PKG"
OUT_DIR="$ROOT/artifacts"

rm -rf "$STAGE"
mkdir -p "$STAGE/DEBIAN" \
         "$STAGE/usr/share/clipd" \
         "$STAGE/usr/bin" \
         "$STAGE/usr/share/applications" \
         "$STAGE/usr/share/icons/hicolor/128x128/apps" \
         "$STAGE/usr/share/icons/hicolor/192x192/apps" \
         "$STAGE/usr/lib/systemd/user" \
         "$OUT_DIR"

# ── 源文件 ──
install -m 0755 "$ROOT/ubuntu_clipd.py" "$STAGE/usr/share/clipd/clipd.py"
install -m 0755 "$ROOT/clipd_ui.py"     "$STAGE/usr/share/clipd/clipd_ui.py"

# ── 图标 ──
install -m 0644 "$ROOT/assets/clipd.png" "$STAGE/usr/share/icons/hicolor/192x192/apps/clipd.png"
install -m 0644 "$ROOT/assets/clipd.png" "$STAGE/usr/share/icons/hicolor/128x128/apps/clipd.png"

# ── .desktop ──
sed "s|__INSTALL_DIR__|/usr/share/clipd|g" "$ROOT/clipd.desktop" \
    > "$STAGE/usr/share/applications/com.clipd.manager.desktop"
chmod 0644 "$STAGE/usr/share/applications/com.clipd.manager.desktop"

# ── /usr/bin/clipd 启动器 ──
cat > "$STAGE/usr/bin/clipd" << 'EOF'
#!/usr/bin/env bash
exec /usr/bin/python3 /usr/share/clipd/clipd.py "$@"
EOF
chmod 0755 "$STAGE/usr/bin/clipd"

cat > "$STAGE/usr/bin/clipd-ui" << 'EOF'
#!/usr/bin/env bash
exec /usr/bin/python3 /usr/share/clipd/clipd_ui.py "$@"
EOF
chmod 0755 "$STAGE/usr/bin/clipd-ui"

# ── systemd user unit ──
cat > "$STAGE/usr/lib/systemd/user/clipd.service" << 'EOF'
[Unit]
Description=Clipd Desktop clipboard sync
After=graphical-session.target

[Service]
ExecStart=/usr/bin/python3 /usr/share/clipd/clipd.py
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF

# ── DEBIAN/control ──
cat > "$STAGE/DEBIAN/control" << EOF
Package: clipd
Version: $VERSION
Section: utils
Priority: optional
Architecture: all
Depends: python3 (>= 3.10), python3-pil, python3-gi, gir1.2-gtk-3.0, wl-clipboard, xclip, libnotify-bin, avahi-utils, python3-qrcode
Recommends: python3-pystray
Maintainer: ar0c <ar0c@users.noreply.github.com>
Homepage: https://github.com/ar0c/clipd
Description: Android <-> Ubuntu clipboard / notification / file sync
 clipd keeps clipboards, notifications and files in sync between an
 Ubuntu desktop and an Android phone over the local network.
EOF

# ── postinst: 更新 icon / desktop 缓存 ──
cat > "$STAGE/DEBIAN/postinst" << 'EOF'
#!/bin/sh
set -e
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t /usr/share/icons/hicolor || true
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications || true
fi
exit 0
EOF
chmod 0755 "$STAGE/DEBIAN/postinst"

cat > "$STAGE/DEBIAN/prerm" << 'EOF'
#!/bin/sh
set -e
exit 0
EOF
chmod 0755 "$STAGE/DEBIAN/prerm"

# ── 打包 ──
dpkg-deb --build --root-owner-group "$STAGE" "$OUT_DIR/${PKG}.deb"
echo "✓ Built $OUT_DIR/${PKG}.deb"
