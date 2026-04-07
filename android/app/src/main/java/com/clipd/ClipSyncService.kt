package com.clipd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.ServerSocket

class ClipSyncService : Service() {

    companion object {
        const val NOTIF_CHANNEL = "clipd_service_v2"
        const val NOTIF_EVENT_CHANNEL = "clipd_sync_alert_v2"
        const val NOTIF_ID = 1
        const val NOTIF_EVENT_ID = 2
        const val GROUP_KEY = "clipd_fg"
        const val ACTION_STATUS = "com.clipd.STATUS"
        const val ACTION_SYNC_CLIP = "com.clipd.SYNC_CLIP"

        @Volatile var instance: ClipSyncService? = null
    }

    private var screenshotObserver: ContentObserver? = null
    private var serverSocket: ServerSocket? = null
    private var clipboardManager: android.content.ClipboardManager? = null
    private var lastImageHash = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryStarted = false

    private var overlayView: android.view.View? = null
    private var lastClipHash = ""

    fun getLastClipHash(): String = lastClipHash
    fun setLastClipHash(hash: String) { lastClipHash = hash }

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        Sync.log("📋 clipListener 触发")
        mainHandler.post { readWithTempFocusableOverlay() }
    }

    private var clipPollRunnable: Runnable? = null
    private var clipReadReceiver: android.content.BroadcastReceiver? = null
    private var heartbeatRunnable: Runnable? = null
    private val heartbeatIntervalMs = 10_000L

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                Sync.executor.execute {
                    val ip = Sync.ubuntuIp
                    if (!ip.isNullOrBlank()) {
                        // 正常心跳：ping 在线的桌面
                        if (!runCatching { Sync.isClipdServerPublic(ip) }.getOrDefault(false)) {
                            Sync.log("⚠ 心跳失败: $ip 不可达")
                            Sync.ubuntuIp = null
                        }
                    } else if (isWifiConnected()) {
                        // 断链恢复：重验保存的 IP，成功则自动重连
                        val saved = Sync.getSavedUbuntuIp(this@ClipSyncService)
                        if (!saved.isNullOrBlank() &&
                            runCatching { Sync.isClipdServerPublic(saved) }.getOrDefault(false)) {
                            Sync.log("心跳重连成功: $saved")
                            mainHandler.post { onDiscovered(saved) }
                        }
                    }
                }
                mainHandler.postDelayed(this, heartbeatIntervalMs)
            }
        }.also { mainHandler.postDelayed(it, heartbeatIntervalMs) }
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun registerClipboardListener() {
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        // 初始化 hash 避免启动时误发
        try {
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) lastClipHash = text.hashCode().toString()
        } catch (_: Exception) {}
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // 1x1 透明悬浮窗：让系统认为 app 在前台，getPrimaryClip() 不返回 null
        if (android.provider.Settings.canDrawOverlays(this)) {
            addOverlay()
            Sync.log("剪切板监听已启动（悬浮窗模式）")
        } else {
            Sync.log("⚠ 未授予悬浮窗权限，后台剪切板同步可能受限")
        }

        // 轮询兜底：尝试直接读取（app 在前台时有效），否则用临时 overlay
        clipPollRunnable = object : Runnable {
            private var pollCount = 0
            override fun run() {
                pollCount++
                try {
                    val clip = clipboardManager?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            val hash = text.hashCode().toString()
                            if (hash != lastClipHash && hash != Sync.lastSentHash) {
                                lastClipHash = hash
                                Sync.log("📤 轮询捕获: ${text.take(40)}")
                                Sync.sendText(text)
                                sendEventNotification("剪切板已同步", "→ ${text.take(60)}")
                            }
                        }
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 3000)
            }
        }
        mainHandler.postDelayed(clipPollRunnable!!, 3000)

        // 接收无障碍服务的"立即读取"广播
        clipReadReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                Sync.log("📋 收到立即读取请求")
                // 多次重试，给系统时间写入剪切板
                val delays = longArrayOf(100, 300, 600, 1200)
                for (delay in delays) {
                    mainHandler.postDelayed({
                        try {
                            val clip = clipboardManager?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0)?.text?.toString()
                                if (!text.isNullOrBlank()) {
                                    val hash = text.hashCode().toString()
                                    if (hash != lastClipHash && hash != Sync.lastSentHash) {
                                        lastClipHash = hash
                                        Sync.log("📤 无障碍触发发送: ${text.take(40)}")
                                        Sync.sendText(text)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }, delay)
                }
            }
        }
        registerReceiver(clipReadReceiver, android.content.IntentFilter("com.clipd.READ_CLIPBOARD_NOW"),
            android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    /** 无障碍服务调用：临时创建可获焦 overlay 读取剪切板 */
    fun readClipboardNow() {
        mainHandler.post { readWithTempFocusableOverlay() }
    }

    /**
     * 临时添加一个可获焦的 overlay，读取剪切板后立即移除。
     * 参考 MacroDroid/Tasker 的 "Clipboard Refresh" 实现。
     */
    private var readAttempt = 0
    private val MAX_READ_ATTEMPTS = 4
    // 每轮间隔：立即、1.5s、3s、5s（越来越长，给 app 切换机会）
    private val RETRY_DELAYS = longArrayOf(0, 1500, 3000, 5000)

    private fun readWithTempFocusableOverlay() {
        readAttempt = 0
        doSingleOverlayRead()
    }

    private fun doSingleOverlayRead() {
        if (readAttempt >= MAX_READ_ATTEMPTS) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val tempView = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val params = android.view.WindowManager.LayoutParams(
            1, 1,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可聚焦但不抢 IME：FLAG_ALT_FOCUSABLE_IM 阻止 WM 把当前 IME 收起或附着到本窗口
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            x = 0; y = 0
            // 显式告诉 WM：本窗口出现/消失都不要动 IME 状态
            softInputMode =
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            wm.addView(tempView, params)
        } catch (e: Exception) { return }

        var removed = false
        fun removeOverlay() {
            if (!removed) {
                removed = true
                try { wm.removeView(tempView) } catch (_: Exception) {}
            }
        }

        var found = false
        val delays = longArrayOf(50, 200, 400)
        for (delay in delays) {
            mainHandler.postDelayed({
                if (removed || found) return@postDelayed
                try {
                    val clip = clipboardManager?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            val hash = text.hashCode().toString()
                            if (hash != lastClipHash && hash != Sync.lastSentHash) {
                                lastClipHash = hash
                                found = true
                                Sync.log("📤 剪切板已捕获: ${text.take(40)}")
                                Sync.sendText(text)
                                sendEventNotification("剪切板已同步", "→ ${text.take(60)}")
                            } else {
                                found = true  // 内容相同，不重复发
                            }
                            removeOverlay()
                        }
                    }
                } catch (_: Exception) {}
            }, delay)
        }

        // 500ms 后移除本轮 overlay，未读到则安排下一轮
        mainHandler.postDelayed({
            removeOverlay()
            if (!found) {
                readAttempt++
                if (readAttempt < MAX_READ_ATTEMPTS) {
                    val nextDelay = RETRY_DELAYS[readAttempt]
                    mainHandler.postDelayed({ doSingleOverlayRead() }, nextDelay)
                }
            }
        }, 500)
    }

    private fun addOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val view = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val params = android.view.WindowManager.LayoutParams(
            1, 1,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            x = 0; y = 0
        }
        wm.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it)
            overlayView = null
        }
    }

    private val onDiscovered: (String) -> Unit = { ip ->
        Sync.saveUbuntuIp(this, ip)
        Sync.resetBackoff()
        updateNotification("已连接 Ubuntu: $ip")
        sendBroadcast(Intent(ACTION_STATUS).putExtra("ubuntu_ip", ip))
        sendEventNotification("已连接 Ubuntu: $ip", "剪切板同步已就绪")
        Sync.log("已发现 Ubuntu: $ip")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                Sync.resetBackoff()
                val savedIp = Sync.getSavedUbuntuIp(this@ClipSyncService)
                if (!savedIp.isNullOrBlank()) {
                    Sync.log("WiFi 已连接，验证 $savedIp ...")
                    Sync.executor.execute {
                        if (Sync.isClipdServerPublic(savedIp)) {
                            Sync.saveUbuntuIp(this@ClipSyncService, savedIp)
                            onDiscovered(savedIp)
                        } else {
                            Sync.log("$savedIp 不可达，开始搜索...")
                            mainHandler.post { startDiscovery() }
                        }
                    }
                } else {
                    Sync.log("WiFi 已连接，开始搜索 Ubuntu")
                    startDiscovery()
                }
            }
        }
        override fun onLost(network: Network) {
            Sync.log("网络断开，暂停同步")
            Sync.ubuntuIp = null
            Sync.stopDiscovery()
            discoveryStarted = false
            updateNotification("网络断开，等待重连...")
            sendBroadcast(Intent(ACTION_STATUS).putExtra("ubuntu_ip", "").putExtra("lost", true))
        }
    }

    private val reconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val delay = intent.getLongExtra("delay", 5_000L)
            mainHandler.postDelayed({
                if (isWifiConnected()) {
                    Sync.log("重新搜索 Ubuntu...")
                    startDiscovery()
                }
            }, delay)
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun startDiscovery() {
        if (discoveryStarted) Sync.stopDiscovery()
        discoveryStarted = true
        Sync.startDiscovery(onDiscovered)
        Sync.startNsdDiscovery(this, onDiscovered)
        // 15 秒后仍未发现则启动跨网段扫描
        mainHandler.postDelayed({
            if (Sync.ubuntuIp == null) {
                Sync.log("UDP 未发现，启动跨网段扫描...")
                Sync.startSubnetScan(onDiscovered)
            }
        }, 15_000)
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Sync.appContext = applicationContext
        createNotificationChannel()
        // 恢复保存的手动 IP
        val savedIp = Sync.getSavedUbuntuIp(this)
        if (!savedIp.isNullOrBlank()) {
            Sync.saveUbuntuIp(this, savedIp)
            Sync.log("已恢复 Ubuntu IP: $savedIp")
        }
        startForeground(NOTIF_ID, buildNotification(
            if (Sync.ubuntuIp != null) "已设置 Ubuntu: ${Sync.ubuntuIp}" else "等待配对..."
        ))
        // WiFi 变化监听
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            networkCallback
        )
        // 退避重连广播
        registerReceiver(reconnectReceiver,
            IntentFilter(Sync.ACTION_CONNECTION_LOST), RECEIVER_NOT_EXPORTED)

        // 只有 WiFi 时才启动发现
        if (isWifiConnected()) startDiscovery()
        startReceiveServer()
        registerScreenshotObserver()
        registerClipboardListener()
        startHeartbeat()
        Log.i(Sync.TAG, "ClipSyncService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC_CLIP) handleSyncClipAction()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopHeartbeat()
        clipPollRunnable?.let { mainHandler.removeCallbacks(it) }
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        removeOverlay()
        screenshotObserver?.let { contentResolver.unregisterContentObserver(it) }
        serverSocket?.close()
        Sync.stopDiscovery()
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            .unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(reconnectReceiver) }
        clipReadReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    // ── 截图监听 ──────────────────────────────────────────────────────────────

    private fun registerScreenshotObserver() {
        val handler = Handler(Looper.getMainLooper())
        screenshotObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return
                Thread { handleNewImage(uri) }.start()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver!!
        )
    }

    private fun handleNewImage(uri: Uri) {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                ?: return
            // Skip .pending- temp files (vivo writes temp file then renames)
            val fileName = path.substringAfterLast("/")
            if (fileName.startsWith(".")) {
                return
            }
            Sync.log("新图片: $path")
            if (!path.contains("screenshot", ignoreCase = true) &&
                !path.contains("截图", ignoreCase = false) &&
                !path.contains("screen", ignoreCase = true) &&
                !path.contains("capture", ignoreCase = true)) {
                Sync.log("非截图，跳过")
                return
            }
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return
            val hash = "${file.name}:${file.length()}"
            if (hash == lastImageHash) return
            lastImageHash = hash
            Sync.log("发送截图: ${file.name} (${file.length()/1024}KB)")
            Sync.sendImage(file)
            updateNotification("截图已同步 → Ubuntu")
        }
    }

    // ── HTTP 服务器（接收来自 Ubuntu 的文字）─────────────────────────────────

    private fun startReceiveServer() {
        Thread {
            try {
                val ss = ServerSocket(Sync.ANDROID_HTTP_PORT).also { serverSocket = it }
                Log.i(Sync.TAG, "HTTP server on :${Sync.ANDROID_HTTP_PORT}")
                while (!ss.isClosed) {
                    val client = ss.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                if (serverSocket?.isClosed == false) Log.e(Sync.TAG, "Server error: ${e.message}")
            }
        }.start()
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.soTimeout = 30_000
            client.use { sock ->
                val input = sock.inputStream
                // 读取 HTTP 请求头（纯 ASCII 逐字节）
                val headerLines = mutableListOf<String>()
                val buf = StringBuilder()
                var prev = -1
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    if (prev == '\r'.code && b == '\n'.code) {
                        val line = buf.toString().trimEnd('\r')
                        if (line.isEmpty()) break
                        headerLines.add(line)
                        buf.clear()
                    } else {
                        buf.append(b.toChar())
                    }
                    prev = b
                }
                val requestLine = headerLines.firstOrNull() ?: ""
                // GET /ping — 用于 Ubuntu 扫描时验证身份
                if (requestLine.startsWith("GET") && requestLine.contains("/ping")) {
                    sock.outputStream.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nCLIPD_OK".toByteArray()
                    )
                    return@use
                }
                // POST /discover — Ubuntu 反向通知手机其 IP（解决单向可达问题）
                if (requestLine.startsWith("POST") && requestLine.contains("/discover")) {
                    val contentLength2 = headerLines
                        .find { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    val body2 = readExactly(input, contentLength2).toString(Charsets.UTF_8)
                    val ip = body2.split("&").find { it.startsWith("ip=") }
                        ?.substringAfter("ip=")?.trim()
                    if (!ip.isNullOrBlank()) {
                        Sync.saveUbuntuIp(this@ClipSyncService, ip)
                        Sync.log("已发现 Ubuntu: $ip")
                        sendBroadcast(Intent(ACTION_STATUS).putExtra("ubuntu_ip", ip))
                    }
                    sock.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    return@use
                }
                val contentLength = headerLines
                    .find { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                val contentType = headerLines
                    .find { it.startsWith("Content-Type:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim() ?: ""
                // 用原始字节读取 body（避免 BufferedReader 损坏二进制数据）
                val bodyBytes = readExactly(input, contentLength)
                if (contentType.contains("multipart", ignoreCase = true)) {
                    parseMultipartImage(contentType, bodyBytes)
                } else {
                    handleTextBody(bodyBytes.toString(Charsets.UTF_8))
                }
                sock.outputStream.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
                )
            }
        } catch (e: Exception) {
            Log.e(Sync.TAG, "Client error: ${e.message}")
        }
    }

    private fun readExactly(input: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == n) buf else buf.copyOf(offset)
    }

    private fun handleTextBody(raw: String) {
        val text = raw.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }["text"] ?: ""
        if (text.isNotBlank()) {
            Sync.lastSentHash = text.hashCode().toString()
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            Handler(Looper.getMainLooper()).post {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("clipd", text))
            }
            updateNotification("文字已同步 ← Ubuntu")
            sendEventNotification("剪切板同步", "← ${text.take(60)}")
            Sync.log("← Ubuntu 文字: ${text.take(40)}")
        }
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
        outer@ for (i in start..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun parseMultipartImage(contentType: String, body: ByteArray) {
        val boundary = contentType.split(";").map { it.trim() }
            .find { it.startsWith("boundary=", ignoreCase = true) }
            ?.substringAfter("=")?.trim() ?: return
        val delimiter = "--$boundary\r\n".toByteArray(Charsets.ISO_8859_1)
        val partStart = indexOfBytes(body, delimiter)
        if (partStart == -1) return
        var pos = partStart + delimiter.size
        val partHeaderEnd = indexOfBytes(body, "\r\n\r\n".toByteArray(), pos)
        if (partHeaderEnd == -1) return
        pos = partHeaderEnd + 4
        val endMarker = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val imageEnd = indexOfBytes(body, endMarker, pos)
        if (imageEnd == -1) return
        val imageBytes = body.copyOfRange(pos, imageEnd)
        if (imageBytes.isEmpty()) return
        Sync.log("← Ubuntu 图片: ${imageBytes.size / 1024}KB")
        saveImageToClipboard(imageBytes)
    }

    private fun saveImageToClipboard(imageBytes: ByteArray) {
        try {
            val dir = java.io.File(cacheDir, "clipd").also { it.mkdirs() }
            val file = java.io.File(dir, "clip_${System.currentTimeMillis()}.png")
            file.writeBytes(imageBytes)
            // 清理旧文件（超过 1 小时）
            dir.listFiles()?.filter {
                it.name != file.name && System.currentTimeMillis() - it.lastModified() > 3_600_000
            }?.forEach { it.delete() }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            Handler(Looper.getMainLooper()).post {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newUri(contentResolver, "clipd_image", uri))
                updateNotification("图片已同步 ← Ubuntu")
                sendEventNotification("图片已同步", "Ubuntu 剪贴板图片已就绪，可直接粘贴")
            }
            Sync.log("← Ubuntu 图片已写入剪切板")
        } catch (e: Exception) {
            Sync.log("✗ 图片写入剪切板失败: ${e.message}")
        }
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL, "clipd 同步服务",
                NotificationManager.IMPORTANCE_LOW).apply { description = "保持剪切板同步" }
        )
        // 删除旧通道（vivo 可能缓存旧图标）
        nm.deleteNotificationChannel("clipd_events")
        nm.deleteNotificationChannel("clipd_service")
        nm.deleteNotificationChannel("clipd_sync_alert")
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_EVENT_CHANNEL, "clipd 同步提醒",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "剪切板同步成功提醒"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun launchIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private var eventNotifCounter = 0
    private val NOTIF_LIVE_ID = 100  // 原子岛专用 ID

    private fun sendEventNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)

        // 1) Heads-up 横幅通知（立即可见）
        val nid = NOTIF_EVENT_ID + (eventNotifCounter++ % 5)
        val bigIcon = buildLauncherBitmap()
        val headsUp = NotificationCompat.Builder(this, NOTIF_EVENT_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(buildSmallIcon(bigIcon))
            .setLargeIcon(bigIcon)
            .setColor(0xFF4A90D9.toInt())
            .setContentIntent(launchIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(false)
            .build()
        nm.notify(nid, headsUp)
        mainHandler.postDelayed({ nm.cancel(nid) }, 5000)

        // 2) 原子岛 / Live Update 通知（Android 16+）
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            postLiveUpdate(nm, title, text)
        }
    }

    @Suppress("NewApi")
    private fun postLiveUpdate(nm: NotificationManager, title: String, text: String) {
        val chip = if (text.startsWith("→")) "已同步" else if (text.startsWith("←")) "已接收" else title.take(4)
        val liveBmp = buildLauncherBitmap()
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(buildSmallIcon(liveBmp))
            .setLargeIcon(liveBmp)
            .setColor(0xFF4A90D9.toInt())
            .setGroup(GROUP_KEY)
            .setGroupSummary(false)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launchIntent())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 手动注入 promoted ongoing extras（无需 core 1.17.0）
        builder.addExtras(android.os.Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putString("android.shortCriticalText", chip)
        })

        // 直接设 FLAG_PROMOTED_ONGOING
        val notif = builder.build()
        notif.flags = notif.flags or Notification.FLAG_PROMOTED_ONGOING

        nm.notify(NOTIF_LIVE_ID, notif)
        // 5秒后移除
        mainHandler.postDelayed({ nm.cancel(NOTIF_LIVE_ID) }, 5000)
    }

    private fun syncClipIntent(): PendingIntent =
        PendingIntent.getService(this, 1,
            Intent(this, ClipSyncService::class.java).setAction(ACTION_SYNC_CLIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildLauncherBitmap(size: Int = 96): android.graphics.Bitmap? = try {
        val dr = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.mipmap.ic_launcher)
        if (dr != null) {
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            dr.setBounds(0, 0, size, size); dr.draw(android.graphics.Canvas(b)); b
        } else null
    } catch (_: Exception) { null }

    private fun buildSmallIcon(bmp: android.graphics.Bitmap?): androidx.core.graphics.drawable.IconCompat =
        if (bmp != null) androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
        else androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_notif)

    private fun buildNotification(text: String): Notification {
        val largeIcon = buildLauncherBitmap()
        val smallIcon = buildSmallIcon(largeIcon)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("clipd")
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setLargeIcon(largeIcon)
            .setColor(0xFF4A90D9.toInt())
            .setOngoing(true)
            .setContentIntent(launchIntent())
            // 前台通知本身就是 group summary，避免双通知 + 折叠时丢失实时状态
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .addAction(android.R.drawable.ic_menu_upload, "同步剪切板", syncClipIntent())
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun handleSyncClipAction() {
        readWithTempFocusableOverlay()
    }
}
