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
        if (Sync.appContext == null) Sync.appContext = applicationContext
        Sync.log("✓ 通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Sync.log("✗ 通知监听服务断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val prefs = getSharedPreferences("clipd", MODE_PRIVATE)
        if (!prefs.getBoolean(PREFS_KEY_ENABLED, true)) return
        if (prefs.getBoolean("manually_stopped", false)) return
        if (ClipSyncService.instance == null) return

        val selected = prefs.getStringSet(PREFS_KEY_PACKAGES, emptySet()) ?: emptySet()
        val mode = prefs.getString(PREFS_KEY_MODE, "exclude")

        val explicitIncluded = mode == "include" && pkg in selected
        if (!explicitIncluded && !isSmsPackage(pkg) && isSystemPackage(pkg)) return

        val forward = if (mode == "exclude") pkg !in selected else pkg in selected
        if (!forward && !isSmsPackage(pkg)) return

        val now = System.currentTimeMillis()
        if (now - (lastSentTime[pkg] ?: 0L) < 2_000) return
        lastSentTime[pkg] = now

        val ip = Sync.ubuntuIp ?: Sync.getSavedUbuntuIp(this)?.also { Sync.ubuntuIp = it }
        if (ip == null) {
            Sync.log("⏭ 通知跳过: ubuntuIp 为空")
            return
        }

        val extras  = sbn.notification.extras
        var title   = extras.getString("android.title") ?: ""
        var text    = extras.getCharSequence("android.text")?.toString() ?: ""
        var appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { pkg }

        // SMS 通知：AccessibilityService 优先处理，NotifListenerService 兜底（锁屏时）
        if (isSmsPackage(pkg)) {
            if (title.isEmpty() && text.isEmpty()) {
                // vivo 的 mms.service 通知无内容，从活跃通知中找短信应用的通知
                val sms = findSmsFromActiveNotifications(sbn.key)
                if (sms != null) {
                    appName = "短信"; title = sms.first; text = sms.second
                } else return
            } else {
                appName = "短信"
            }
            // 如果 AccessibilityService 已转发过相同内容，跳过
            if (text == Sync.lastSmsForwardText &&
                System.currentTimeMillis() - Sync.lastSmsForwardTime < 5_000) return
            Sync.lastSmsForwardText = text
            Sync.lastSmsForwardTime = System.currentTimeMillis()
        }

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

    private var lastActiveNotifText = ""

    /** 从活跃通知中查找短信内容（遍历所有通知找 SMS 相关应用的带内容通知） */
    private fun findSmsFromActiveNotifications(excludeKey: String): Pair<String, String>? {
        return try {
            val all = activeNotifications ?: return null
            for (n in all) {
                if (n.key == excludeKey) continue
                if (!isSmsPackage(n.packageName) && n.packageName != "com.android.mms") continue
                val extras = n.notification.extras ?: continue
                val t = extras.getString("android.title") ?: ""
                val b = extras.getCharSequence("android.text")?.toString() ?: ""
                if (b.isNotBlank() && b != lastActiveNotifText) {
                    lastActiveNotifText = b
                    Sync.log("📱 SMS(active): $t / ${b.take(30)}")
                    return Pair(t, b)
                }
            }
            // 也尝试从触发通知自身的 tickerText 或 bigText 提取
            null
        } catch (e: Exception) {
            Sync.log("⚠ activeNotifications 异常: ${e.message}")
            null
        }
    }

    /** 短信/电话相关系统应用，默认放行（SMS 内容由 AccessibilityService 转发） */
    private fun isSmsPackage(pkg: String): Boolean {
        val smsApps = setOf(
            "com.android.mms", "com.android.mms.service",
            "com.android.messaging", "com.google.android.apps.messaging",
            "com.vivo.message", "com.samsung.android.messaging",
            "com.android.phone", "com.vivo.contacts"
        )
        return pkg in smsApps
    }

    /** 系统应用判定：FLAG_SYSTEM 或 FLAG_UPDATED_SYSTEM_APP，加上 vivo/android 系统包前缀兜底 */
    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg == "android" || pkg.startsWith("com.android.") ||
            pkg.startsWith("com.vivo.") || pkg.startsWith("com.bbk.") ||
            pkg.startsWith("com.iqoo.")) return true
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) { false }
    }
}
