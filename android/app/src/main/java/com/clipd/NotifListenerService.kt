package com.clipd

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

class NotifListenerService : NotificationListenerService() {

    companion object {
        const val PREFS_KEY_ENABLED  = "notif_mirror_enabled"
        const val PREFS_KEY_PACKAGES = "notif_mirror_packages"
        const val PREFS_KEY_MODE     = "notif_mirror_mode"   // "include" | "exclude"
    }

    // 去抖：同一应用 2 秒内只转发一次
    private val lastSentTime = mutableMapOf<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Sync.log("✓ 通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Sync.log("✗ 通知监听服务断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return   // 跳过自身通知

        Sync.log("📩 收到通知: $pkg")

        val prefs = getSharedPreferences("clipd", MODE_PRIVATE)
        if (!prefs.getBoolean(PREFS_KEY_ENABLED, true)) {
            Sync.log("⏭ 通知转发已禁用")
            return
        }

        val selected = prefs.getStringSet(PREFS_KEY_PACKAGES, emptySet()) ?: emptySet()
        val mode = prefs.getString(PREFS_KEY_MODE, "exclude")
        val forward = if (mode == "exclude") pkg !in selected else pkg in selected
        if (!forward) {
            Sync.log("⏭ 通知被过滤: $pkg")
            return
        }

        val now = System.currentTimeMillis()
        if (now - (lastSentTime[pkg] ?: 0L) < 2_000) {
            Sync.log("⏭ 通知去抖: $pkg")
            return
        }
        lastSentTime[pkg] = now

        val ip = Sync.ubuntuIp ?: Sync.getSavedUbuntuIp(this)?.also { Sync.ubuntuIp = it }
        if (ip == null) {
            Sync.log("⏭ 通知跳过: ubuntuIp 为空")
            return
        }

        val extras  = sbn.notification.extras
        val title   = extras.getString("android.title") ?: ""
        val text    = extras.getCharSequence("android.text")?.toString() ?: ""
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { pkg }

        Sync.executor.execute {
            try {
                val body = """{"appName":"${appName.esc()}","title":"${title.esc()}","text":"${text.esc()}"}"""
                    .toByteArray(Charsets.UTF_8)
                val conn = URL("http://$ip:${Sync.UBUNTU_HTTP_PORT}/notify")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout   = 5_000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body) }
                conn.responseCode
                conn.disconnect()
                Sync.log("→ Ubuntu 通知: $appName / $title")
            } catch (e: Exception) {
                Sync.log("⚠ 通知转发失败: ${e.message}")
                Log.w(Sync.TAG, "通知转发失败: ${e.message}")
            }
        }
    }

    private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "")
}
