package com.clipd

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClipboardAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastHash = ""
    private var pendingCheck = false

    // 检测"复制"相关的关键词
    private val copyKeywords = listOf(
        "复制", "拷贝", "copy", "Copy", "COPY",
        "已复制", "已拷贝", "Copied", "copied"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        // 初始化 hash
        try {
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) lastHash = text.hashCode().toString()
        } catch (_: Exception) {}
        Log.i(Sync.TAG, "ClipboardAccessibilityService connected")
        Sync.log("无障碍剪切板监听已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 用户点击了按钮 — 检查是否是"复制"按钮
                val text = event.text?.joinToString("") ?: ""
                val desc = event.contentDescription?.toString() ?: ""
                if (copyKeywords.any { text.contains(it) || desc.contains(it) }) {
                    scheduleClipboardCheck()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Toast / popup 出现（如"已复制到剪贴板"）
                val text = event.text?.joinToString("") ?: ""
                if (copyKeywords.any { text.contains(it) }) {
                    scheduleClipboardCheck()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 某些系统在复制时触发 content changed
                val text = event.text?.joinToString("") ?: ""
                if (copyKeywords.any { text.contains(it) }) {
                    scheduleClipboardCheck()
                }
            }
        }
    }

    private fun scheduleClipboardCheck() {
        if (pendingCheck) return
        pendingCheck = true
        // 延迟 300ms 让剪切板数据就绪
        handler.postDelayed({
            pendingCheck = false
            readAndSync()
        }, 300)
    }

    private fun readAndSync() {
        try {
            val cm = clipboardManager ?: return
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                val hash = text.hashCode().toString()
                if (hash != lastHash && hash != Sync.lastSentHash) {
                    lastHash = hash
                    Sync.sendText(text)
                }
            }
        } catch (e: Exception) {
            Sync.log("⚠ 无障碍读剪切板失败: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
    }
}
