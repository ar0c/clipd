package com.clipd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：检测复制动作，触发 ClipSyncService（有 overlay）读取剪切板。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastHash = ""
    private var pendingCheck = false

    private val clickKeywords = listOf("复制", "拷贝", "Copy", "COPY")
    private val toastKeywords = listOf(
        "已复制", "已拷贝", "Copied", "copied",
        "复制成功", "已复制到剪贴板", "内容已复制"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Sync.appContext == null) Sync.appContext = applicationContext
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        try {
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) lastHash = text.hashCode().toString()
        } catch (_: Exception) {}
        Log.w(Sync.TAG, "ClipboardAccessibilityService connected")
        Sync.log("无障碍剪切板监听已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val eventText = event.text?.joinToString("") ?: ""
        val pkg = event.packageName?.toString() ?: ""
        val desc = event.contentDescription?.toString() ?: ""

        if (pkg == "com.android.systemui" || pkg == "com.vivo.timerwidget" ||
            pkg == "com.vivo.notification") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (clickKeywords.any { eventText == it || desc == it }) {
                    Sync.log("📋 复制按钮: $eventText ($pkg)")
                    scheduleClipboardCheck()
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (eventText.length < 50 && toastKeywords.any { eventText.contains(it) }) {
                    Sync.log("📋 复制确认: $eventText ($pkg)")
                    scheduleClipboardCheck()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (eventText.length < 50 && toastKeywords.any { eventText.contains(it) }) {
                    Sync.log("📋 窗口确认: $eventText ($pkg)")
                    scheduleClipboardCheck()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (eventText.length < 30 && toastKeywords.any { eventText.contains(it) }) {
                    scheduleClipboardCheck()
                }
            }
        }
    }

    private fun scheduleClipboardCheck() {
        if (pendingCheck) return
        pendingCheck = true

        // 触发 ClipSyncService（有 overlay 权限）读取剪切板
        handler.postDelayed({
            val svc = ClipSyncService.instance
            if (svc != null) {
                svc.readClipboardNow()
            } else {
                Sync.log("⚠ ClipSyncService 未运行")
            }
            pendingCheck = false
        }, 150)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy() }
}
