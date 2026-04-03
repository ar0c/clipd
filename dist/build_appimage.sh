#!/usr/bin/env bash
# 构建 Clipd 桌面端 Ubuntu AppImage（单文件可执行，无需安装）
# 用法: ./dist/build_appimage.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
APPDIR="$SCRIPT_DIR/AppDir"
VERSION="1.0.11"
ARCH="x86_64"
OUTPUT="$SCRIPT_DIR/clipd-${VERSION}-${ARCH}.AppImage"

echo "==> 检查依赖..."
command -v python3 >/dev/null || { echo "需要 python3"; exit 1; }
APPIMAGETOOL=$(command -v appimagetool 2>/dev/null || echo "")
if [ -z "$APPIMAGETOOL" ]; then
    echo "==> 下载 appimagetool..."
    APPIMAGETOOL="$SCRIPT_DIR/appimagetool-x86_64.AppImage"
    if [ ! -f "$APPIMAGETOOL" ]; then
        curl -L -o "$APPIMAGETOOL" \
            "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
        chmod +x "$APPIMAGETOOL"
    fi
fi

echo "==> 创建 AppDir..."
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/clipd" "$APPDIR/usr/share/applications"

# 主程序
cp "$ROOT/ubuntu_clipd.py" "$APPDIR/usr/share/clipd/clipd.py"

# 入口脚本
cat > "$APPDIR/AppRun" << 'EOF'
#!/usr/bin/env bash
APPDIR="$(dirname "$(readlink -f "$0")")"
exec python3 "$APPDIR/usr/share/clipd/clipd.py" "$@"
EOF
chmod +x "$APPDIR/AppRun"
ln -sf AppRun "$APPDIR/usr/bin/clipd"

# .desktop
cat > "$APPDIR/clipd.desktop" << EOF
[Desktop Entry]
Name=Clipd Desktop
Name[zh_CN]=Clipd 桌面端
Comment=Android ↔ Ubuntu clipboard sync
Comment[zh_CN]=Android ↔ Ubuntu 剪贴板同步
Exec=clipd
Icon=clipd
Type=Application
Categories=Utility;
EOF
cp "$APPDIR/clipd.desktop" "$APPDIR/usr/share/applications/"

# 图标（使用 Python 生成简单绿圆）
python3 - "$APPDIR/clipd.png" << 'PYEOF'
import sys
from PIL import Image, ImageDraw
img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
draw.ellipse([20, 20, 236, 236], fill=(46, 204, 113, 255))
img.save(sys.argv[1])
PYEOF

echo "==> 检查运行时依赖（系统需要安装）..."
for cmd in wl-copy wl-paste xclip avahi-publish-service notify-send; do
    command -v "$cmd" >/dev/null && echo "  ✓ $cmd" || echo "  ✗ $cmd（缺失，运行时可能报错）"
done
python3 -c "import pystray, qrcode, PIL" 2>/dev/null && echo "  ✓ python 依赖" || \
    echo "  ✗ python 依赖缺失，请先: pip3 install pystray 'qrcode[pil]' pillow"

echo "==> 构建 AppImage..."
ARCH="$ARCH" "$APPIMAGETOOL" "$APPDIR" "$OUTPUT"
echo ""
echo "✓ 构建完成: $OUTPUT"
echo "  运行: ./$OUTPUT"
echo "  开机自启: cp ~/.config/systemd/user/clipd.service（手动创建）"
