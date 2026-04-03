# clipd

Android ↔ Ubuntu 双向剪贴板同步工具，支持文字和截图图片。

无需第三方账号，局域网自动发现，开箱即用。

## 功能

- 📋 **文字双向同步**：手机复制 → 电脑，电脑复制 → 手机
- 📸 **截图自动发送**：手机截图后自动推送到电脑剪贴板，可直接粘贴到飞书等应用
- 🔍 **自动发现**：mDNS（`_clipd._tcp`）+ UDP 广播双重发现，同一 WiFi 下无需配置
- 🌐 **跨网络支持**：Tailscale 等 VPN 场景下可手动输入 IP

## 系统要求

| 端 | 要求 |
|---|---|
| Ubuntu | 22.04+，Wayland（GNOME） |
| Android | 8.0+（API 26+） |

## 安装

### Ubuntu 端

**方法一：deb 包（推荐）**

```bash
sudo apt install ./clipd_1.0.0_amd64.deb
systemctl --user daemon-reload
systemctl --user enable --now clipd
```

依赖自动安装：`python3-pil` `wl-clipboard` `xclip` `avahi-utils` `libnotify-bin`

**方法二：从源码安装**

```bash
git clone <repo-url>
cd clipd
./install.sh
```

### Android 端

安装 `clipd-debug.apk`（位于 `dist/` 或 GitHub Releases）。

## 使用

### 基本使用（局域网）

1. Ubuntu 和手机连接**同一 WiFi**
2. Ubuntu 服务已在后台运行（安装后自动启动）
3. 打开手机 clipd App → 点击「前往开启」→ 开启**辅助功能**
4. 点击「开始同步」

App 状态栏变为「已发现 Ubuntu: xxx.xxx.xxx.xxx」即配对成功，无需手动输入任何 IP。

> 辅助功能用于监听剪贴板变化（手机复制 → 电脑方向），截图自动发送不依赖辅助功能。

### 跨网络使用（Tailscale 等）

在 App 输入框填入 Ubuntu 的 IP（如 Tailscale IP `100.x.x.x`），点击「设置」即可。

### 同步行为

| 操作 | 结果 |
|---|---|
| 手机截图 | 自动发送到电脑剪贴板 |
| 手机复制文字（需辅助功能）| 自动同步到电脑剪贴板 |
| 电脑复制文字 | 自动推送到手机剪贴板 |
| 电脑复制图片 | 自动推送到手机剪贴板 |

### Ubuntu 服务管理

```bash
systemctl --user status clipd       # 查看状态
systemctl --user restart clipd      # 重启
journalctl --user -u clipd -f       # 实时日志
```

## 网络端口

| 端口 | 协议 | 用途 |
|---|---|---|
| 8888 | TCP | Ubuntu 接收端（Android → Ubuntu）|
| 8889 | TCP | Android 接收端（Ubuntu → Android）|
| 8890 | UDP | 局域网广播自动发现 |

## 架构

```
Android App                       Ubuntu Daemon
────────────────────              ──────────────────────────
ClipSyncService                   ubuntu_clipd.py
 ├─ ContentObserver               ├─ HTTP Server :8888
 │   └─ 监听截图                  │   ├─ 接收图片 → wl-copy (PNG)
 ├─ ClipboardAccessibility        │   └─ 接收文字 → wl-copy
 │   └─ 监听剪贴板变化             ├─ wl-paste --watch
 ├─ HTTP Server :8889             │   └─ 监听剪贴板 → POST Android
 │   └─ 接收 Ubuntu 文字          ├─ avahi-publish-service
 └─ NsdManager                    │   └─ mDNS 广播 _clipd._tcp
     └─ 发现 _clipd._tcp           └─ UDP Broadcast :8890
```

**图片传输：**
手机截图 → ContentObserver → MediaStore URI 读取 → HTTP POST → Ubuntu 接收 → Pillow 转 PNG → wl-copy → GNOME 桥接到 XWayland → 可粘贴

## 从源码构建

### Android

```bash
cd android
export ANDROID_HOME=$HOME/Android/sdk
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

### Ubuntu

无需编译，Python 脚本直接运行。

## 卸载

```bash
# Ubuntu
sudo apt remove clipd

# Android：系统设置 → 应用 → clipd → 卸载
```

## 故障排查

**手机找不到 Ubuntu（自动发现失败）**
- 确认手机和 Ubuntu 在同一 WiFi
- 检查 Ubuntu 服务：`systemctl --user status clipd`
- 查看 mDNS 是否广播：`avahi-browse _clipd._tcp`
- 跨网络时改用手动输入 IP

**截图没有发送**
- 查看 App 内日志（主界面底部）
- 确认已点击「开始同步」且状态不是「已停止」

**飞书粘贴出来是乱码**
- RustDesk 占用了 X11 剪贴板：RustDesk → 设置 → 安全 → 关闭「剪贴板共享」

**输入法无法使用**
- 旧版 bug，已修复。确保使用最新版本（`journalctl --user -u clipd | grep version`）
