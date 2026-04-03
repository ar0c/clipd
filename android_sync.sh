#!/data/data/com.termux/files/usr/bin/bash
# clipd - Android 端剪切板同步脚本（Termux）
# 依赖: pkg install inotify-tools termux-api python

set -euo pipefail

# 申请 wake lock，防止后台被杀
termux-wake-lock 2>/dev/null || true

UBUNTU_PORT=8888
ANDROID_PORT=8889
DISCOVERY_PORT=8890
SCREENSHOT_DIR="$HOME/storage/pictures/Screenshots"

# ── 自动发现 Ubuntu IP ────────────────────────────────────────────────────────

discover_ubuntu() {
    echo "[clipd] 正在发现 Ubuntu..." >&2
    python3 - "$DISCOVERY_PORT" "$ANDROID_PORT" << 'PYEOF'
import sys, socket, time

discovery_port = int(sys.argv[1])
android_port   = int(sys.argv[2])

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.settimeout(30)
sock.bind(('', discovery_port))

while True:
    try:
        data, addr = sock.recvfrom(256)
        msg = data.decode()
        if msg.startswith('CLIPD_SERVER:'):
            ubuntu_ip = addr[0]
            # 回复自己的端口
            reply = f'CLIPD_CLIENT:{android_port}'.encode()
            sock.sendto(reply, (ubuntu_ip, discovery_port))
            print(ubuntu_ip)  # 只输出 IP，供 shell 捕获
            break
    except socket.timeout:
        print('TIMEOUT', file=sys.stderr)
        break
PYEOF
}

# 支持手动指定 IP（跳过发现）
if [ -n "${1:-}" ]; then
    UBUNTU_IP="$1"
    echo "[clipd] 使用手动指定 IP: $UBUNTU_IP"
else
    UBUNTU_IP=$(discover_ubuntu)
    if [ -z "$UBUNTU_IP" ] || [ "$UBUNTU_IP" = "TIMEOUT" ]; then
        echo "[clipd] 未找到 Ubuntu，请确保 ubuntu_clipd.py 已运行" >&2
        exit 1
    fi
    echo "[clipd] 已发现 Ubuntu: $UBUNTU_IP"
fi

echo "[clipd] Android 端启动"
echo "  截图目录: $SCREENSHOT_DIR"
echo "  Ubuntu: $UBUNTU_IP:$UBUNTU_PORT"
echo "  本机监听: :$ANDROID_PORT"
echo ""

# ── 接收服务器（Python）──────────────────────────────────────────────────────

cat > /tmp/clipd_server.py << 'PYEOF'
import sys, http.server, socketserver, urllib.parse, io, cgi, subprocess, os

PORT = int(sys.argv[1])

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        ct = self.headers.get('Content-Type', '')
        ln = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(ln)

        if 'multipart' in ct:
            env = {'REQUEST_METHOD': 'POST', 'CONTENT_TYPE': ct, 'CONTENT_LENGTH': str(ln)}
            form = cgi.FieldStorage(fp=io.BytesIO(body), environ=env)
            if 'image' in form:
                data = form['image'].file.read()
                # Android 无法直接设置图片剪切板，保存到文件并通知
                path = '/tmp/clipd_recv.png'
                with open(path, 'wb') as f:
                    f.write(data)
                subprocess.Popen([
                    'termux-notification', '--title', 'clipd',
                    '--content', f'图片已接收，保存至 {path}',
                    '--action', f'termux-share {path}'
                ])
                print(f'[clipd] ← Ubuntu: 图片 ({len(data)//1024}KB)', flush=True)
        else:
            params = urllib.parse.parse_qs(body.decode('utf-8', 'replace'))
            text = params.get('text', [''])[0]
            if text:
                subprocess.run(['termux-clipboard-set', text])
                subprocess.Popen([
                    'termux-notification', '--title', 'clipd',
                    '--content', f'文字已同步 ← Ubuntu: {text[:40]}'
                ])
                print(f'[clipd] ← Ubuntu: 文字 {repr(text[:40])}', flush=True)

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'ok')

    def log_message(self, *a):
        pass

socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(('0.0.0.0', PORT), Handler) as s:
    print(f'[clipd] 接收服务器监听 :{PORT}', flush=True)
    s.serve_forever()
PYEOF

python3 /tmp/clipd_server.py "$ANDROID_PORT" &
SERVER_PID=$!

# ── 截图监听（inotifywait）──────────────────────────────────────────────────

screenshot_watcher() {
    local last_hash=""
    inotifywait -m "$SCREENSHOT_DIR" -e close_write --format '%f' 2>/dev/null |
    while read -r file; do
        [[ "$file" =~ \.(png|jpg|jpeg|webp)$ ]] || continue
        sleep 0.3
        local path="$SCREENSHOT_DIR/$file"
        local h
        h=$(md5sum "$path" 2>/dev/null | cut -c1-12) || continue
        [ "$h" = "$last_hash" ] && continue
        last_hash="$h"
        curl -s -X POST "http://$UBUNTU_IP:$UBUNTU_PORT/clipboard" \
             -F "image=@$path" -o /dev/null && \
        echo "[clipd] → Ubuntu: 截图 $file"
    done
}

# ── 剪切板监听（轮询）──────────────────────────────────────────────────────

clipboard_watcher() {
    local last=""
    while true; do
        local current
        current=$(termux-clipboard-get 2>/dev/null) || { sleep 1; continue; }
        if [ -n "$current" ] && [ "$current" != "$last" ]; then
            last="$current"
            curl -s -X POST "http://$UBUNTU_IP:$UBUNTU_PORT/clipboard" \
                 --data-urlencode "text=$current" -o /dev/null && \
            echo "[clipd] → Ubuntu: 文字 ${current:0:40}"
        fi
        sleep 0.8
    done
}

screenshot_watcher &
INOTIFY_PID=$!

clipboard_watcher &
CLIP_PID=$!

echo "[clipd] 所有监听已启动，按 Ctrl+C 停止"

cleanup() {
    echo ""
    echo "[clipd] 停止中..."
    kill "$SERVER_PID" "$INOTIFY_PID" "$CLIP_PID" 2>/dev/null || true
    exit 0
}

trap cleanup INT TERM
wait
