package com.clipd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.HttpURLConnection
import java.net.URL

class ClipboardAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckTime = 0L

    private val copyWords = listOf("复制", "拷贝", "Copy", "COPY", "copy")

    private val toastKeywords = listOf(
        "已复制", "已拷贝", "Copied", "copied",
        "复制成功", "内容已复制", "快去粘贴",
        "复制完成", "链接已复制", "口令已复制"
    )

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Sync.log("📋 [a11y-listener] 剪切板变化")
        scheduleClipboardCheck()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Sync.appContext == null) Sync.appContext = applicationContext
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                         AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        Log.w(Sync.TAG, "ClipboardAccessibilityService connected")
        Sync.log("无障碍剪切板监听已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val eventText = event.text?.joinToString("") ?: ""
        val pkg = event.packageName?.toString() ?: ""
        val desc = event.contentDescription?.toString() ?: ""

        if (pkg == "com.android.systemui" || pkg == "com.vivo.timerwidget" ||
            pkg == "com.vivo.notification" || pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                // 1) 直接匹配 event text / contentDescription
                if (hasCopyWord(eventText) || hasCopyWord(desc)) {
                    Sync.log("📋 复制按钮: $eventText | $desc ($pkg)")
                    scheduleClipboardCheck()
                    return
                }
                // 2) 遍历点击源节点及周围节点，找"复制"文字
                val source = event.source
                if (source != null && nodeTreeContainsCopy(source)) {
                    Sync.log("📋 复制节点: ($pkg)")
                    scheduleClipboardCheck()
                    source.recycle()
                    return
                }
                source?.recycle()
                // 3) 通用兜底：任何点击后 1.5 秒冷却检查一次
                scheduleGeneralCheck()
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 短信通知：从无障碍事件直接提取内容
                if (pkg == "com.android.mms" || pkg == "com.android.mms.service" ||
                    pkg == "com.vivo.message") {
                    handleSmsNotification(event)
                }
                if (eventText.length in 1..200 && toastKeywords.any { eventText.contains(it) }) {
                    Sync.log("📋 复制确认: ${eventText.take(40)} ($pkg)")
                    scheduleClipboardCheck()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (eventText.length in 1..200 && toastKeywords.any { eventText.contains(it) }) {
                    Sync.log("📋 复制确认: ${eventText.take(40)} ($pkg)")
                    scheduleClipboardCheck()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (eventText.length in 1..200 && toastKeywords.any { eventText.contains(it) }) {
                    Sync.log("📋 内容变化: ${eventText.take(40)} ($pkg)")
                    scheduleClipboardCheck()
                }
            }
        }
    }

    private fun hasCopyWord(s: String): Boolean =
        s.isNotEmpty() && copyWords.any { s.contains(it) }

    /** 检查节点自身 + 父节点的直接子节点中是否有"复制"文字 */
    private fun nodeTreeContainsCopy(node: AccessibilityNodeInfo): Boolean {
        // 自身
        val selfText = node.text?.toString() ?: ""
        val selfDesc = node.contentDescription?.toString() ?: ""
        if (hasCopyWord(selfText) || hasCopyWord(selfDesc)) return true
        // 子节点（最多 5 个）
        val childCount = minOf(node.childCount, 5)
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val ct = child.text?.toString() ?: ""
            val cd = child.contentDescription?.toString() ?: ""
            val found = hasCopyWord(ct) || hasCopyWord(cd)
            child.recycle()
            if (found) return true
        }
        // 父节点的子节点（兄弟节点）
        val parent = node.parent ?: return false
        val sibCount = minOf(parent.childCount, 8)
        for (i in 0 until sibCount) {
            val sib = parent.getChild(i) ?: continue
            val st = sib.text?.toString() ?: ""
            val sd = sib.contentDescription?.toString() ?: ""
            val found = hasCopyWord(st) || hasCopyWord(sd)
            sib.recycle()
            if (found) return true
        }
        parent.recycle()
        return false
    }

    /** 明确匹配到复制关键词，立即检查 */
    private fun scheduleClipboardCheck() {
        handler.removeCallbacks(clipCheckRunnable)
        lastCheckTime = System.currentTimeMillis()
        handler.postDelayed(clipCheckRunnable, 300)
    }

    /** 通用点击兜底，1.5 秒冷却 */
    private fun scheduleGeneralCheck() {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < 1500) return
        handler.removeCallbacks(clipCheckRunnable)
        lastCheckTime = now
        handler.postDelayed(clipCheckRunnable, 500)
    }

    private val clipCheckRunnable = Runnable {
        val svc = ClipSyncService.instance
        if (svc != null) {
            svc.readClipboardNow()
        } else {
            Sync.log("⚠ ClipSyncService 未运行")
        }
    }

    private var lastSmsText = ""

    private fun handleSmsNotification(event: AccessibilityEvent) {
        val notif = event.parcelableData as? Notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val ticker = notif.tickerText?.toString() ?: ""

        // 从 event.text、extras、tickerText 中提取内容
        val eventText = event.text?.joinToString(" ") ?: ""
        val content = when {
            text.isNotBlank() -> text
            ticker.isNotBlank() -> ticker
            eventText.isNotBlank() -> eventText
            else -> return
        }
        val sender = if (title.isNotBlank()) title else event.packageName?.toString() ?: "短信"

        // 去重
        if (content == lastSmsText) return
        lastSmsText = content

        // 标记已转发，供 NotifListenerService 去重
        Sync.lastSmsForwardText = content
        Sync.lastSmsForwardTime = System.currentTimeMillis()
        Sync.log("📱 SMS(a11y): sender=$sender text=${content.take(30)}")

        val prefs = getSharedPreferences("clipd", MODE_PRIVATE)
        if (!prefs.getBoolean(NotifListenerService.PREFS_KEY_ENABLED, true)) return
        if (prefs.getBoolean("manually_stopped", false)) return

        val ip = Sync.ubuntuIp ?: Sync.getSavedUbuntuIp(this)?.also { Sync.ubuntuIp = it } ?: return

        Sync.executor.execute {
            try {
                val body = """{"appName":"短信","title":"${sender.esc()}","text":"${content.esc()}"}"""
                    .toByteArray(Charsets.UTF_8)
                val conn = URL("http://$ip:${Sync.UBUNTU_HTTP_PORT}/notify")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body) }
                conn.responseCode
                conn.disconnect()
                Sync.log("→ Ubuntu 短信: $sender / ${content.take(20)}")
            } catch (e: Exception) {
                Sync.log("⚠ 短信转发失败: ${e.message}")
            }
        }
    }

    private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "")

    override fun onInterrupt() {}

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }
}
