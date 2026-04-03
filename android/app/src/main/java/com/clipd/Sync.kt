package com.clipd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object Sync {
    const val TAG = "clipd"
    const val PREFS_NAME = "clipd"
    const val KEY_UBUNTU_IP = "ubuntu_ip"
    const val UBUNTU_HTTP_PORT = 8888
    const val ANDROID_HTTP_PORT = 8889
    const val DISCOVERY_PORT = 8890
    const val ACTION_LOG = "com.clipd.LOG"
    const val ACTION_SYNC_ERROR = "com.clipd.SYNC_ERROR"

    @Volatile var ubuntuIp: String? = null
    @Volatile var lastSentHash = ""
    @Volatile var appContext: Context? = null

    internal val executor = Executors.newCachedThreadPool()
    private var discoverySocket: DatagramSocket? = null

    // 连接退避
    const val ACTION_CONNECTION_LOST = "com.clipd.CONNECTION_LOST"
    @Volatile private var consecutiveFailures = 0
    @Volatile private var reconnectDelay = 5_000L

    fun resetBackoff() {
        consecutiveFailures = 0
        reconnectDelay = 5_000L
    }

    private fun onSendSuccess() {
        consecutiveFailures = 0
        reconnectDelay = 5_000L
    }

    private fun onSendFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= 3) {
            val delay = reconnectDelay
            reconnectDelay = minOf(reconnectDelay * 2, 300_000L)
            consecutiveFailures = 0
            ubuntuIp = null
            log("连接失败，${delay / 1000}s 后重新搜索...")
            appContext?.sendBroadcast(
                android.content.Intent(ACTION_CONNECTION_LOST).putExtra("delay", delay)
            )
        }
    }

    private val logBuffer = ArrayDeque<String>(200)

    fun getSavedUbuntuIp(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_UBUNTU_IP, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun saveUbuntuIp(context: Context, ip: String?) {
        val normalized = ip?.trim()?.takeIf { it.isNotEmpty() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UBUNTU_IP, normalized ?: "")
            .apply()
        ubuntuIp = normalized
    }

    fun log(msg: String) {
        Log.w(TAG, msg)
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val line = "[${fmt.format(java.util.Date())}] $msg"
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            if (logBuffer.size > 200) logBuffer.removeFirst()
        }
        // 写文件日志（vivo 屏蔽 logcat）
        try {
            val ctx = appContext ?: return@log
            val file = java.io.File(ctx.filesDir, "clipd.log")
            file.appendText(line + "\n")
            // 限制文件大小 200KB
            if (file.length() > 200_000) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(500).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
        appContext?.sendBroadcast(
            android.content.Intent(ACTION_LOG).putExtra("msg", msg)
        )
    }

    fun getLogHistory(): List<String> = synchronized(logBuffer) { logBuffer.toList() }
    fun clearLog() = synchronized(logBuffer) { logBuffer.clear() }

    private fun resolveIp(): String? {
        ubuntuIp?.let { return it }
        val ctx = appContext ?: return null
        return getSavedUbuntuIp(ctx)?.also { ubuntuIp = it }
    }

    fun sendText(text: String) {
        val ip = resolveIp() ?: run { log("⏭ sendText 跳过: ubuntuIp 为空"); return }
        val hash = text.hashCode().toString()
        if (hash == lastSentHash) return
        lastSentHash = hash
        executor.execute {
            try {
                val url = URL("http://$ip:$UBUNTU_HTTP_PORT/clipboard")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = "text=${URLEncoder.encode(text, "UTF-8")}"
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
                conn.disconnect()
                onSendSuccess()
                log("→ Ubuntu 文字: ${text.take(40)}")
            } catch (e: Exception) {
                onSendFailure()
                log("✗ 发送文字失败: ${e.message}")
                appContext?.sendBroadcast(
                    android.content.Intent(ACTION_SYNC_ERROR).putExtra("msg", e.message ?: "连接失败")
                )
            }
        }
    }

    fun sendImage(file: File) {
        val ip = resolveIp() ?: run { log("⏭ sendImage 跳过: ubuntuIp 为空"); return }
        executor.execute {
            try {
                val boundary = "clipdboundary"
                val url = URL("http://$ip:$UBUNTU_HTTP_PORT/clipboard")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                DataOutputStream(conn.outputStream).use { out ->
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"${file.name}\"\r\n")
                    out.writeBytes("Content-Type: image/png\r\n\r\n")
                    FileInputStream(file).use { it.copyTo(out) }
                    out.writeBytes("\r\n--$boundary--\r\n")
                }
                val code = conn.responseCode
                conn.disconnect()
                onSendSuccess()
                log("→ Ubuntu 图片 ${file.name}: HTTP $code")
            } catch (e: Exception) {
                onSendFailure()
                log("✗ 发送失败: ${e.message}")
                appContext?.sendBroadcast(
                    android.content.Intent(ACTION_SYNC_ERROR).putExtra("msg", e.message ?: "连接失败")
                )
            }
        }
    }

    fun startDiscovery(onDiscovered: (String) -> Unit) {
        executor.execute {
            try {
                val sock = DatagramSocket(DISCOVERY_PORT).also { discoverySocket = it }
                val buf = ByteArray(256)
                Log.i(TAG, "UDP discovery on :$DISCOVERY_PORT")
                while (!sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length)
                    if (msg.startsWith("CLIPD_SERVER:")) {
                        val ip = pkt.address.hostAddress ?: continue
                        if (ubuntuIp != ip) {
                            ubuntuIp = ip
                            Log.i(TAG, "Ubuntu discovered: $ip")
                            onDiscovered(ip)
                        }
                        val reply = "CLIPD_CLIENT:$ANDROID_HTTP_PORT".toByteArray()
                        sock.send(DatagramPacket(reply, reply.size, pkt.address, DISCOVERY_PORT))
                    }
                }
            } catch (e: Exception) {
                if (discoverySocket?.isClosed == false) Log.e(TAG, "Discovery error: ${e.message}")
            }
        }
    }

    /** 跨网段扫描：探测 A.B.0~10.x 的 Ubuntu HTTP 端口 */
    fun startSubnetScan(onDiscovered: (String) -> Unit) {
        executor.execute {
            val found = java.util.concurrent.atomic.AtomicBoolean(false)

            fun tryIp(ip: String) {
                if (found.get()) return
                if (isClipdServer(ip) && found.compareAndSet(false, true)) {
                    ubuntuIp = ip
                    Log.i(TAG, "Found Ubuntu: $ip")
                    onDiscovered(ip)
                }
            }

            // ① ARP 表：覆盖所有网段，快速
            val arpIps = readArpTable()
            if (arpIps.isNotEmpty()) {
                log("ARP 发现 ${arpIps.size} 个邻居，探测中...")
                val sem = java.util.concurrent.Semaphore(64)
                for (ip in arpIps) {
                    if (found.get()) break
                    sem.acquire()
                    executor.execute { try { tryIp(ip) } finally { sem.release() } }
                }
                // 等待所有 ARP 探测完成
                sem.acquire(64); sem.release(64)
            }
            if (found.get()) return@execute

            // ② 子网扫描补充
            val myIp = getLocalWifiIp() ?: return@execute
            val parts = myIp.split(".")
            if (parts.size != 4) return@execute
            val a = parts[0]; val b = parts[1]; val myC = parts[2].toInt()
            val candidates = mutableListOf<String>()
            val thirds = (0..10).toMutableSet().also { it.add(myC) }
            for (c in thirds) for (h in 1..254) candidates.add("$a.$b.$c.$h")
            // 常见家用段补充
            if (a != "192") for (c in 0..20) for (h in 1..254) candidates.add("192.168.$c.$h")
            if (a != "10")  for (c in 0..5)  for (h in 1..254) candidates.add("10.0.$c.$h")

            log("子网扫描 ${candidates.size} 个候选...")
            val sem = java.util.concurrent.Semaphore(128)
            for (ip in candidates) {
                if (found.get()) break
                sem.acquire()
                executor.execute { try { tryIp(ip) } finally { sem.release() } }
            }
        }
    }

    private fun readArpTable(): List<String> {
        return try {
            java.io.File("/proc/net/arp").readLines()
                .drop(1) // 跳过标题行
                .mapNotNull { line ->
                    val cols = line.trim().split("\\s+".toRegex())
                    cols.getOrNull(0)?.takeIf {
                        it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                        cols.getOrNull(2) != "0x0"  // 跳过不完整条目
                    }
                }
        } catch (_: Exception) { emptyList() }
    }

    /** TCP 连通 + HTTP /ping 验证是 clipd server（internal 供 MainActivity 调用）*/
    fun isClipdServerPublic(ip: String) = isClipdServer(ip)

    private fun isClipdServer(ip: String): Boolean {
        return try {
            val url = java.net.URL("http://$ip:$UBUNTU_HTTP_PORT/ping")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200 &&
                conn.inputStream.bufferedReader().readText().trim() == "CLIPD_OK"
            conn.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    private fun getLocalWifiIp(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.firstOrNull { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                ?.inetAddresses?.toList()
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    fun stopDiscovery() {
        discoverySocket?.close()
        discoverySocket = null
    }

    fun startNsdDiscovery(context: Context, onDiscovered: (String) -> Unit) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD resolve failed: $errorCode")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val ip = info.host?.hostAddress ?: return
                if (ubuntuIp != ip) {
                    ubuntuIp = ip
                    Log.i(TAG, "NSD discovered Ubuntu: $ip")
                    onDiscovered(ip)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.i(TAG, "NSD started") }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.e(TAG, "NSD start failed: $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == "clipd") {
                    nsdManager.resolveService(info, resolveListener)
                }
            }
        }

        nsdManager.discoverServices("_clipd._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        Log.i(TAG, "NSD discovery started for _clipd._tcp")
    }
}
