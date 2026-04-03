package com.clipd

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button

/**
 * 最小化输入法服务：利用 Android 对 IME 的剪切板豁免来读取剪切板。
 * Android 10+ 明确豁免当前默认 IME 对 getPrimaryClip() 的调用。
 *
 * 键盘 UI 只显示一个"切换键盘"按钮，不影响正常输入。
 * 用户需要打字时点击按钮切换到真正的输入法，clipd 保持为默认输入法。
 */
class ClipboardIMEService : InputMethodService() {

    private var clipboardManager: ClipboardManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastHash = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Sync.log("📋 [IME] OnPrimaryClipChanged 触发")
        val delays = longArrayOf(0, 100, 300)
        for (delay in delays) {
            handler.postDelayed({ readAndSync() }, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Sync.appContext == null) Sync.appContext = applicationContext
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // 初始化 hash
        try {
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) lastHash = text.hashCode().toString()
            Sync.log("✓ IME 剪切板服务已启动, 初始: ${text?.take(30) ?: "null"}")
        } catch (e: Exception) {
            Sync.log("✓ IME 剪切板服务已启动, 初始读取异常: ${e.message}")
        }

        startPoll()
    }

    private fun readAndSync() {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip == null || clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) return

            val hash = text.hashCode().toString()
            if (hash == lastHash || hash == Sync.lastSentHash) return
            lastHash = hash
            ClipSyncService.instance?.setLastClipHash(hash)
            Sync.log("📤 [IME] 发送: ${text.take(40)}")
            Sync.sendText(text)
        } catch (e: Exception) {
            Sync.log("⚠ [IME] 读取异常: ${e.message}")
        }
    }

    private var pollRunnable: Runnable? = null

    private fun startPoll() {
        pollRunnable = object : Runnable {
            override fun run() {
                readAndSync()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(pollRunnable!!, 3000)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_keyboard, null)
        view.findViewById<Button>(R.id.btnSwitchIME).setOnClickListener {
            switchToNextInputMethod(false)
        }
        return view
    }

    override fun onDestroy() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        Sync.log("✗ IME 剪切板服务已停止")
        super.onDestroy()
    }
}
