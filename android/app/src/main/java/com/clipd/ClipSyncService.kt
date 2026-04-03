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
        const val NOTIF_CHANNEL = "clipd_service"
        const val NOTIF_EVENT_CHANNEL = "clipd_events"
        const val NOTIF_ID = 1
        const val NOTIF_EVENT_ID = 2
        const val ACTION_STATUS = "com.clipd.STATUS"
        const val ACTION_SYNC_CLIP = "com.clipd.SYNC_CLIP"
    }

    private var screenshotObserver: ContentObserver? = null
    private var serverSocket: ServerSocket? = null
    private var clipboardManager: android.content.ClipboardManager? = null
    private var lastImageHash = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryStarted = false

    private var overlayView: android.view.View? = null
    private var lastClipHash = ""

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        val cm = clipboardManager ?: return@OnPrimaryClipChangedListener
        try {
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                val hash = text.hashCode().toString()
                if (hash != lastClipHash && hash != Sync.lastSentHash) {
                    lastClipHash = hash
                    Sync.sendText(text)
                }
            }
        } catch (_: Exception) {}
    }

    private var clipPollRunnable: Runnable? = null

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

        // vivo 等 OEM 不触发 OnPrimaryClipChangedListener，轮询兜底
        clipPollRunnable = object : Runnable {
            override fun run() {
                try {
                    val clip = clipboardManager?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            val hash = text.hashCode().toString()
                            if (hash != lastClipHash && hash != Sync.lastSentHash) {
                                lastClipHash = hash
                                Sync.sendText(text)
                            }
                        }
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 3000)
            }
        }
        mainHandler.postDelayed(clipPollRunnable!!, 3000)
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
        Log.i(Sync.TAG, "ClipSyncService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC_CLIP) handleSyncClipAction()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clipPollRunnable?.let { mainHandler.removeCallbacks(it) }
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        removeOverlay()
        screenshotObserver?.let { contentResolver.unregisterContentObserver(it) }
        serverSocket?.close()
        Sync.stopDiscovery()
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            .unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(reconnectReceiver) }
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
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_EVENT_CHANNEL, "clipd 事件",
                NotificationManager.IMPORTANCE_DEFAULT).apply { description = "连接和同步事件" }
        )
    }

    private fun launchIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun sendEventNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, NOTIF_EVENT_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_EVENT_ID, notif)
    }

    private fun syncClipIntent(): PendingIntent =
        PendingIntent.getService(this, 1,
            Intent(this, ClipSyncService::class.java).setAction(ACTION_SYNC_CLIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("clipd")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(launchIntent())
            .addAction(android.R.drawable.ic_menu_upload, "同步剪切板", syncClipIntent())
            .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun handleSyncClipAction() {
        val cm = clipboardManager ?: return
        try {
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Sync.log("⚠ 无法读取剪切板")
                return
            }
            val text = clip.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                Sync.sendText(text)
            } else {
                Sync.log("剪切板无文字内容")
            }
        } catch (e: Exception) {
            Sync.log("⚠ 读取剪切板失败: ${e.message}")
        }
    }
}
