package com.clipd

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * 透明 Activity：短暂获取前台状态以读取剪切板。
 * vivo 等 OEM 限制后台 getPrimaryClip()，必须 app 真正可见才能读取。
 * 检测到复制动作时由 AccessibilityService 启动，读取后立即 finish()。
 */
class ClipReaderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 完全透明，不抢焦点
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.setDimAmount(0f)

        // 多次尝试读取
        val handler = Handler(Looper.getMainLooper())
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val delays = longArrayOf(0, 100, 300, 600)
        var found = false

        for (delay in delays) {
            handler.postDelayed({
                if (found) return@postDelayed
                try {
                    val clip = cm.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            val hash = text.hashCode().toString()
                            val svc = ClipSyncService.instance
                            val lastHash = svc?.getLastClipHash() ?: ""
                            if (hash != lastHash && hash != Sync.lastSentHash &&
                                svc != null &&
                                !getSharedPreferences(Sync.PREFS_NAME, MODE_PRIVATE)
                                    .getBoolean("manually_stopped", false)) {
                                found = true
                                svc.setLastClipHash(hash)
                                Sync.log("📤 [前台读取] 成功: ${text.take(40)}")
                                Sync.sendText(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Sync.log("⚠ [前台读取] 异常: ${e.message}")
                }
            }, delay)
        }

        // 最迟 800ms 后关闭
        handler.postDelayed({ finish() }, 800)
    }

    override fun finish() {
        super.finish()
        // 无动画关闭
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
