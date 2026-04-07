# clipd

Clipd 桌面端 + Android App 的局域网同步工具，支持双向剪贴板、截图推送和通知镜像。

无需第三方账号，局域网自动发现，开箱即用。

## 功能

- 📋 **文字双向同步**：手机复制 → 电脑，电脑复制 → 手机
- 📸 **截图自动发送**：手机截图后自动推送到电脑剪贴板，可直接粘贴到飞书等应用
- 🔔 **通知镜像**：Android 通知可转发到 Ubuntu 桌面通知中心
- 🔍 **自动发现**：mDNS（`_clipd._tcp`）+ UDP 广播双重发现，同一 WiFi 下无需配置
- 🌐 **跨网络支持**：Tailscale 等 VPN 场景下可手动输入 IP

## 系统要求

| 端 | 要求 |
|---|---|
| Ubuntu | 22.04+，Wayland（GNOME） |
| Android | 8.0+（API 26+），推荐 Android 16（API 36）获得最佳体验 |

## 安装

### Ubuntu 端

Ubuntu 端安装后在应用菜单中的名字是 `Clipd Desktop` / `Clipd 桌面端`，但命令名、systemd 服务名和协议名仍然保持 `clipd`。

**方法一：deb 包（推荐）**

```bash
sudo apt install ./dist/clipd_1.0.0_amd64.deb
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

安装 `dist/clipd-debug.apk`。

## 使用

### 基本使用（局域网）

1. Ubuntu 和手机连接**同一 WiFi**
2. Ubuntu 服务已在后台运行（安装后自动启动）
3. 打开手机 clipd App，完成配对
4. 开启必要权限：
   - **悬浮窗权限**（必须）：后台读取剪贴板的核心机制
   - **辅助功能**（推荐）：检测复制动作，触发即时同步
   - **通知使用权**（可选）：通知镜像到电脑
5. 点击「开始同步」

App 状态栏变为「已发现 Ubuntu: xxx.xxx.xxx.xxx」即配对成功。

### Android 剪贴板同步原理

Android 10+ 限制后台读取剪贴板。clipd 使用**临时可获焦悬浮窗**方案（与 MacroDroid/Tasker 相同原理）：

1. 辅助功能服务检测到用户复制操作
2. 前台服务临时创建一个可获焦的悬浮窗（`TYPE_APPLICATION_OVERLAY`，不带 `FLAG_NOT_FOCUSABLE`）
3. 在窗口获焦期间调用 `getPrimaryClip()` 读取剪贴板
4. 读取完成后立即移除悬浮窗，归还焦点
5. 如果首次未读到，会自动重试最多 4 轮（间隔递增：0/1.5/3/5 秒）

#### 复制检测机制

辅助功能服务通过多种方式检测复制动作：

- **按钮文字匹配**：eventText/contentDescription 包含"复制"、"拷贝"、"Copy"等
- **节点树遍历**：检查点击源的子节点和兄弟节点中是否有复制相关文字（解决淘宝等自定义 UI）
- **Toast/Snackbar 检测**："已复制"、"快去粘贴"等确认文字
- **通用兜底**：任何点击事件后 1.5 秒冷却检查剪贴板变化（覆盖无法通过关键词匹配的场景）

> **备选方案：**
> - **InputMethodService**：将 clipd 设为默认输入法，利用 IME 剪贴板豁免。需要切换键盘。
> - **ADB 白名单**：`adb shell appops set com.clipd READ_CLIPBOARD allow`，一次授权后无需任何额外操作。

### 通知样式

- **Heads-up 横幅通知**：复制成功后弹出顶部通知横幅，5 秒后自动消失
- **Android 16 原子岛**（实验性）：设置 `FLAG_PROMOTED_ONGOING` + `requestPromotedOngoing` extra，需设备支持且用户授权实时活动权限

### 跨网络使用（Tailscale 等）

在 App 输入框填入 Ubuntu 的 IP（如 Tailscale IP `100.x.x.x`），点击「设置」即可。

### 同步行为

| 操作 | 结果 |
|---|---|
| 手机截图 | 自动发送到电脑剪贴板 |
| 手机复制文字 | 自动同步到电脑剪贴板（需悬浮窗+辅助功能） |
| 电脑复制文字 | 自动推送到手机剪贴板 |
| 电脑复制图片 | 自动推送到手机剪贴板 |
| 手机收到通知 | 自动转发到 Ubuntu 通知中心（需通知使用权） |

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
 ├─ AccessibilityService          │   └─ 接收文字 → wl-copy
 │   ├─ 按钮/Toast/节点树检测      ├─ wl-paste --watch
 │   └─ 通用点击兜底检测           │   └─ 监听剪贴板 → POST Android
 ├─ 临时可获焦 Overlay（多轮重试）  ├─ avahi-publish-service
 │   └─ getPrimaryClip()          │   └─ mDNS 广播 _clipd._tcp
 ├─ HTTP Server :8889             └─ UDP Broadcast :8890
 │   └─ 接收 Ubuntu 文字/图片
 └─ NsdManager
     └─ 发现 _clipd._tcp
```

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

**手机复制后没有同步到电脑**
- 确认已授予悬浮窗权限
- 确认辅助功能已开启
- 打开 clipd 查看日志，是否有 `📤 剪切板已捕获` 记录
- 如果日志显示 `clip=null`，尝试备选方案（IME 或 ADB 白名单）

**部分 app 的复制按钮无法触发同步**
- 自定义 UI 的复制按钮（如淘宝、Keep 分享面板）可能不产生标准无障碍事件
- clipd 会在任意点击后以 1.5 秒冷却检查剪贴板，可能存在短暂延迟
- 多轮重试机制会在 1.5/3/5 秒后自动重试读取

**手机找不到 Ubuntu（自动发现失败）**
- 确认手机和 Ubuntu 在同一 WiFi
- 检查 Ubuntu 服务：`systemctl --user status clipd`
- 查看 mDNS 是否广播：`avahi-browse _clipd._tcp`
- 跨网络时改用手动输入 IP

**电脑上收不到 Android 通知**
- 确认手机里 `通知镜像` 开关已打开
- 确认 Android 已授予 `clipd` 通知使用权
- 如果用了应用筛选，检查当前是 `include` 还是 `exclude` 模式

**截图没有发送**
- 查看 App 内日志
- 确认已点击「开始同步」且状态不是「已停止」

**飞书粘贴出来是乱码**
- RustDesk 占用了 X11 剪贴板：RustDesk → 设置 → 安全 → 关闭「剪贴板共享」

**vivo / OriginOS 通知图标显示为默认 Android 机器人**

vivo OriginOS 5+ 有个隐藏白名单 `allow_notification_applist_v3`，只允许列表里的 app（系统应用 + 微信/QQ/飞书/网易等）使用自定义通知小图标，其他一律替换为系统默认 glyph。还有个 `statusbar_notification_icon_redraw` 决定是否重绘。

`adb shell dumpsys notification` 能确认通知 icon 正确送达（`typ=BITMAP` 或 `typ=RESOURCE`），但状态栏仍显示默认图标，就是被 SystemUI 在渲染时替换了。

**解决（需要连接 adb，一次性设置）**：

```bash
# 1. 查看当前白名单
CUR=$(adb shell "settings get secure allow_notification_applist_v3" | tr -d '\r')
echo "$CUR"

# 2. 把 com.clipd 追加进去
adb shell "settings put secure allow_notification_applist_v3 '${CUR}com.clipd;'"

# 3. 关闭状态栏图标重绘（默认 0 = vivo 风格重绘为默认图标；1 = 保留 app 原图标）
adb shell "settings put system statusbar_notification_icon_redraw 1"

# 4. 重启 SystemUI 和 clipd 生效
adb shell "am force-stop com.android.systemui"
adb shell "am force-stop com.clipd"
```

下拉通知中心就能看到 clipd 的蓝色剪贴板图标了。

**注意**：
- 系统 OTA 升级可能重置这两个设置，届时重新执行即可
- 对 OPPO ColorOS / 小米 MIUI 等同源方案可能也有类似隐藏白名单，关键词换成对应 ROM 的 secure setting 名
- 正式发布场景的"合规"做法：接 vivo Push SDK 并在 dev.vivo.com.cn 注册，服务端自动加白（个人项目不现实）
