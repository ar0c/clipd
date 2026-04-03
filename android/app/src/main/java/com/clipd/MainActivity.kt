package com.clipd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
import android.content.ComponentName
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class AppState { STOPPED, NO_WIFI, SEARCHING, CONNECTED, RECONNECTING }

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var cardStatus: android.view.View
    private lateinit var viewStatusDot: android.view.View
    private lateinit var pbSearching: ProgressBar
    private lateinit var tvAccessibility: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnAccessibility: Button
    private lateinit var tvLogBadge: TextView
    private lateinit var tvIMEStatus: TextView
    private lateinit var btnIMEEnable: Button
    private lateinit var tvNotifStatus: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var switchNotifMirror: Switch
    private lateinit var btnPickApps: Button
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var isRunning = false
    private var logSheet: BottomSheetDialog? = null
    private var sheetTvLog: TextView? = null
    private var sheetScrollLog: ScrollView? = null
    private var reconnectCountdown: CountDownTimer? = null
    private var currentState = AppState.STOPPED

    companion object {
        const val PREFS = Sync.PREFS_NAME
        const val KEY_IP = Sync.KEY_UBUNTU_IP
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ClipSyncService.ACTION_STATUS -> {
                    val ip = intent.getStringExtra("ubuntu_ip") ?: return
                    if (intent.getBooleanExtra("lost", false) || ip.isBlank()) {
                        setState(AppState.NO_WIFI, "WiFi 断开或 Ubuntu 离线，等待重连...")
                    } else {
                        setState(AppState.CONNECTED, ip)
                    }
                }
                Sync.ACTION_SYNC_ERROR -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    if (currentState != AppState.RECONNECTING)
                        setState(AppState.RECONNECTING, msg.take(60))
                }
                Sync.ACTION_CONNECTION_LOST -> {
                    val delay = intent.getLongExtra("delay", 5_000L)
                    setState(AppState.RECONNECTING, delayMs = delay)
                }
                Sync.ACTION_LOG -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    when {
                        msg.startsWith("→ Ubuntu") && isRunning -> {
                            val ip = Sync.ubuntuIp
                            if (!ip.isNullOrBlank()) setState(AppState.CONNECTED, ip)
                        }
                        msg.startsWith("已发现 Ubuntu") || msg.startsWith("NSD discovered") -> {
                            val ip = Sync.ubuntuIp
                            if (!ip.isNullOrBlank()) setState(AppState.CONNECTED, ip)
                        }
                        msg.startsWith("重新搜索") || msg.startsWith("WiFi 已连接") -> {
                            if (isRunning) setState(AppState.SEARCHING)
                        }
                        msg.startsWith("网络断开") -> {
                            if (isRunning) setState(AppState.NO_WIFI, "WiFi 断开，等待重连...")
                        }
                        msg.startsWith("✗") && isRunning -> {
                            if (currentState != AppState.RECONNECTING)
                                setState(AppState.RECONNECTING, msg.removePrefix("✗ ").take(60))
                        }
                    }
                    appendLog(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tvVersion).text =
            "剪切板同步 v${packageManager.getPackageInfo(packageName, 0).versionName}"
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        cardStatus = findViewById(R.id.cardStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        pbSearching = findViewById(R.id.pbSearching)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        btnToggle = findViewById(R.id.btnToggle)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvLogBadge = findViewById(R.id.tvLogBadge)
        findViewById<Button>(R.id.btnShowLog).setOnClickListener { showLogSheet() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        tvIMEStatus = findViewById(R.id.tvIMEStatus)
        btnIMEEnable = findViewById(R.id.btnIMEEnable)
        btnIMEEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        switchNotifMirror = findViewById(R.id.switchNotifMirror)
        btnPickApps = findViewById(R.id.btnPickApps)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        switchNotifMirror.isChecked = prefs.getBoolean(NotifListenerService.PREFS_KEY_ENABLED, false)
        switchNotifMirror.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(NotifListenerService.PREFS_KEY_ENABLED, checked).apply()
        }
        btnNotifAccess.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        btnPickApps.setOnClickListener { showAppPickerDialog() }

        // 恢复保存的 IP
        val savedIp = prefs.getString(KEY_IP, "")
        if (!savedIp.isNullOrBlank()) {
            Sync.ubuntuIp = savedIp
        }

        // 处理 QR 码深链接: clipd://connect?ip=x.x.x.x&port=8888
        handleDeepLink(intent)

        btnToggle.setOnClickListener {
            if (isRunning) stopSync() else startSync()
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val filter = IntentFilter(ClipSyncService.ACTION_STATUS).also {
            it.addAction(Sync.ACTION_LOG)
            it.addAction(Sync.ACTION_SYNC_ERROR)
            it.addAction(Sync.ACTION_CONNECTION_LOST)
        }
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        requestRequiredPermissions()

        // 确保 appContext 已设置，使 Sync.log() 广播能实时到达
        Sync.appContext = applicationContext
        // 立即显示已有日志
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        syncServiceState()
        updateAccessibilityStatus()
        updateIMEStatus()
        updateNotifListenerStatus()
    }

    @Suppress("DEPRECATION")
    private fun syncServiceState() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ClipSyncService::class.java.name }
        val wasRunning = isRunning
        if (running != isRunning) {
            isRunning = running
            btnToggle.text = if (running) "停止同步" else "开始同步"
        }
        if (!running) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val startedOnce = prefs.getBoolean("started_once", false)
            if (!startedOnce) {
                // 首次使用，自动开始搜索
                startSync()
                return
            }
            val reason = when {
                wasRunning -> "服务已停止（可能被系统回收）"
                else -> {
                    val savedIp = prefs.getString(KEY_IP, "")
                    if (savedIp.isNullOrBlank()) "或打开 Ubuntu 端扫描二维码配对"
                    else if (!isWifiConnected()) "请先连接 WiFi"
                    else ""
                }
            }
            setState(AppState.STOPPED, reason)
        } else {
            // Service is running — reconcile UI with actual network state
            if (!isWifiConnected()) {
                if (currentState != AppState.NO_WIFI)
                    setState(AppState.NO_WIFI, "WiFi 断开，等待重连...")
            } else if (currentState == AppState.STOPPED) {
                val ip = Sync.ubuntuIp
                if (!ip.isNullOrBlank()) setState(AppState.CONNECTED, ip)
                else setState(AppState.SEARCHING)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateAccessibilityStatus()
            updateIMEStatus()
            updateNotifListenerStatus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun showLogSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_log, null)
        val tvLog = view.findViewById<TextView>(R.id.tvLog)
        val scrollLog = view.findViewById<ScrollView>(R.id.scrollLog)
        sheetTvLog = tvLog
        sheetScrollLog = scrollLog

        // 加载历史日志
        val history = Sync.getLogHistory()
        tvLog.text = if (history.isNotEmpty()) history.joinToString("\n") + "\n" else ""
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }

        view.findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            tvLog.text = ""
            Sync.clearLog()
            updateLogBadge(0)
        }
        sheet.setContentView(view)
        sheet.setOnDismissListener { sheetTvLog = null; sheetScrollLog = null }
        sheet.show()
        logSheet = sheet
        updateLogBadge(0)
    }

    private fun appendLog(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        // 如果 sheet 打开，实时追加
        sheetTvLog?.let { tv ->
            tv.append(line)
            sheetScrollLog?.post { sheetScrollLog?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        // 更新 badge 计数（sheet 未打开时）
        if (sheetTvLog == null) {
            val count = Sync.getLogHistory().size
            updateLogBadge(count)
        }
    }

    private fun updateLogBadge(count: Int) {
        tvLogBadge.text = if (count > 0) "日志  ·  $count 条新记录" else "日志"
    }

    private fun refreshLog() {
        updateLogBadge(Sync.getLogHistory().size)
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectCountdown?.cancel()
        unregisterReceiver(statusReceiver)
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "clipd" && uri.host == "connect") {
            val ip = uri.getQueryParameter("ip") ?: return
            if (ip.isNotBlank()) {
                Sync.saveUbuntuIp(this, ip)
                Sync.log("通过二维码配对: $ip")
                Toast.makeText(this, "已配对: $ip", Toast.LENGTH_SHORT).show()
                if (isRunning) setState(AppState.SEARCHING, ip)
            }
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val et = EditText(this).apply {
            hint = "留空自动发现（同一 WiFi）"
            setText(prefs.getString(KEY_IP, ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Ubuntu IP 地址")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val ip = et.text.toString().trim()
                Sync.saveUbuntuIp(this, ip.ifBlank { null })
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(et.windowToken, 0)
                if (ip.isBlank()) {
                    Toast.makeText(this, "已切换为自动发现", Toast.LENGTH_SHORT).show()
                    if (isRunning && isWifiConnected()) setState(AppState.SEARCHING)
                } else {
                    Toast.makeText(this, "IP 已设置: $ip", Toast.LENGTH_SHORT).show()
                    if (isRunning) verifyAndConnect(ip)
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .also { it.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
    }

    private fun verifyAndConnect(ip: String) {
        setState(AppState.SEARCHING, "验证 $ip ...")
        Sync.executor.execute {
            val ok = Sync.isClipdServerPublic(ip)
            runOnUiThread {
                if (ok) {
                    Sync.saveUbuntuIp(this, ip)
                    setState(AppState.CONNECTED, ip)
                    Sync.log("已发现 Ubuntu: $ip")
                } else {
                    setState(AppState.RECONNECTING, "无法连接到 $ip")
                }
            }
        }
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityEnabled()) {
            tvAccessibility.text = "✓ 剪切板自动同步已启用"
            tvAccessibility.setTextColor(getColor(R.color.connected))
            btnAccessibility.text = "设置"
        } else {
            tvAccessibility.text = "⚠ 需开启辅助功能以同步剪切板"
            tvAccessibility.setTextColor(getColor(R.color.warning))
            btnAccessibility.text = "前往开启"
        }
    }

    private fun updateNotifListenerStatus() {
        if (isNotificationListenerEnabled()) {
            tvNotifStatus.text = "✓ 通知镜像已授权"
            tvNotifStatus.setTextColor(getColor(R.color.connected))
            btnNotifAccess.text = "设置"
        } else {
            tvNotifStatus.text = "通知镜像 Android→Ubuntu"
            tvNotifStatus.setTextColor(getColor(R.color.text_secondary))
            btnNotifAccess.text = "前往授权"
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, NotifListenerService::class.java)
        return flat.split(":").any { it.equals(cn.flattenToString(), ignoreCase = true) }
    }

    private fun showAppPickerDialog() {
        val pm = packageManager
        // 全部可启动 app，按名称排序，去重（同包名可能有多个 activity）
        val allApps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).distinctBy { it.activityInfo.packageName }
         .sortedBy { it.loadLabel(pm).toString() }
        val allNames = allApps.map { it.loadLabel(pm).toString() }
        val allPkgs  = allApps.map { it.activityInfo.packageName }

        val prefs    = getSharedPreferences(PREFS, MODE_PRIVATE)
        val selected = prefs.getStringSet(NotifListenerService.PREFS_KEY_PACKAGES, emptySet())!!
            .toMutableSet()
        val isExclude = prefs.getString(NotifListenerService.PREFS_KEY_MODE, "exclude") == "exclude"

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val rgMode  = dialogView.findViewById<RadioGroup>(R.id.rgMode)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearch)
        val lvApps  = dialogView.findViewById<ListView>(R.id.lvApps)

        rgMode.check(if (isExclude) R.id.rbExclude else R.id.rbInclude)

        // 当前展示的过滤后列表（索引→原始索引）
        var filteredPkgs  = allPkgs.toMutableList()
        var filteredNames = allNames.toMutableList()

        fun rebuildList(query: String) {
            val q = query.trim()
            if (q.isBlank()) {
                filteredPkgs  = allPkgs.toMutableList()
                filteredNames = allNames.toMutableList()
            } else {
                val pairs = allNames.zip(allPkgs).filter { (name, _) ->
                    name.contains(q, ignoreCase = true)
                }
                filteredNames = pairs.map { it.first }.toMutableList()
                filteredPkgs  = pairs.map { it.second }.toMutableList()
            }
            val names = filteredNames.toTypedArray()
            val checks = BooleanArray(filteredPkgs.size) { filteredPkgs[it] in selected }
            lvApps.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_multiple_choice, names
            )
            lvApps.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            checks.forEachIndexed { i, v -> lvApps.setItemChecked(i, v) }
        }

        rebuildList("")

        lvApps.setOnItemClickListener { _, _, pos, _ ->
            val pkg = filteredPkgs[pos]
            if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                rebuildList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("通知转发应用")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val mode = if (rgMode.checkedRadioButtonId == R.id.rbExclude) "exclude" else "include"
                prefs.edit()
                    .putStringSet(NotifListenerService.PREFS_KEY_PACKAGES, selected)
                    .putString(NotifListenerService.PREFS_KEY_MODE, mode)
                    .apply()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateIMEStatus() {
        if (isIMEEnabled()) {
            tvIMEStatus.text = "✓ 输入法剪切板已启用"
            tvIMEStatus.setTextColor(getColor(R.color.connected))
            btnIMEEnable.text = "设置"
        } else {
            tvIMEStatus.text = "⚠ 需开启输入法以读取剪切板"
            tvIMEStatus.setTextColor(getColor(R.color.warning))
            btnIMEEnable.text = "前往开启"
        }
    }

    private fun isIMEEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/$packageName.ClipboardAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            var found = false
            for (s in splitter) { if (s.equals(service, ignoreCase = true)) { found = true; break } }
            found
        } catch (e: Exception) { false }
    }

    // ── 状态管理 ──────────────────────────────────────────────────────────────

    private fun setState(state: AppState, detail: String = "", delayMs: Long = 0) {
        reconnectCountdown?.cancel()
        reconnectCountdown = null
        currentState = state

        // 搜索中用旋转动画，其它状态用彩色圆点
        val isSearching = state == AppState.SEARCHING
        pbSearching.visibility = if (isSearching) android.view.View.VISIBLE else android.view.View.GONE
        viewStatusDot.visibility = if (isSearching) android.view.View.INVISIBLE else android.view.View.VISIBLE

        when (state) {
            AppState.STOPPED -> {
                tvStatus.text = "未运行"
                tvStatus.setTextColor(getColor(R.color.text_secondary))
                tvStatusSub.text = detail.ifBlank { "点击「开始同步」启动服务" }
                cardStatus.background = getDrawable(R.drawable.status_bg_idle)
                viewStatusDot.background = getDrawable(R.drawable.dot_gray)
            }
            AppState.NO_WIFI -> {
                tvStatus.text = "无 WiFi"
                tvStatus.setTextColor(getColor(R.color.warning))
                tvStatusSub.text = detail.ifBlank { "请连接 WiFi 后使用同步" }
                cardStatus.background = getDrawable(R.drawable.status_bg_warning)
                viewStatusDot.background = getDrawable(R.drawable.dot_orange)
            }
            AppState.SEARCHING -> {
                tvStatus.text = "搜索中"
                tvStatus.setTextColor(getColor(R.color.searching))
                tvStatusSub.text = detail.ifBlank { "扫描同一 WiFi 下的 Ubuntu..." }
                cardStatus.background = getDrawable(R.drawable.status_bg_searching)
            }
            AppState.CONNECTED -> {
                tvStatus.text = "已连接"
                tvStatus.setTextColor(getColor(R.color.connected))
                tvStatusSub.text = detail
                cardStatus.background = getDrawable(R.drawable.status_bg_connected)
                viewStatusDot.background = getDrawable(R.drawable.dot_green)
            }
            AppState.RECONNECTING -> {
                tvStatus.text = "重连中"
                tvStatus.setTextColor(getColor(R.color.warning))
                cardStatus.background = getDrawable(R.drawable.status_bg_warning)
                viewStatusDot.background = getDrawable(R.drawable.dot_orange)
                if (delayMs > 0) {
                    reconnectCountdown = object : CountDownTimer(delayMs, 1000) {
                        override fun onTick(millisUntilFinished: Long) {
                            tvStatusSub.text = "${millisUntilFinished / 1000}s 后重新搜索..."
                        }
                        override fun onFinish() {
                            if (isRunning) setState(AppState.SEARCHING)
                        }
                    }.start()
                } else {
                    tvStatusSub.text = detail.ifBlank { "等待重新搜索..." }
                }
            }
        }
    }

    private fun startSync() {
        startForegroundService(Intent(this, ClipSyncService::class.java))
        isRunning = true
        btnToggle.text = "停止同步"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("started_once", true).apply()
        // 无 WiFi 也启动服务（剪切板监听不依赖网络）
        if (!isWifiConnected()) {
            setState(AppState.SEARCHING, "无 WiFi，剪切板监听已启动")
        } else {
            val ip = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_IP, "")
            if (!ip.isNullOrBlank()) verifyAndConnect(ip)
            else setState(AppState.SEARCHING)
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun stopSync() {
        stopService(Intent(this, ClipSyncService::class.java))
        isRunning = false
        btnToggle.text = "开始同步"
        setState(AppState.STOPPED, "已手动停止")
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty())
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 0)
        // 悬浮窗权限（后台剪切板同步必需）
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("Android 限制后台读取剪切板。\n授予悬浮窗权限后，clipd 可在后台自动同步剪切板。")
                .setPositiveButton("去授权") { _, _ ->
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("跳过", null)
                .show()
        }
    }
}
