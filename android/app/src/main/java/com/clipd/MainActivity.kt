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

        // Edge-to-edge: 把状态栏 / 导航栏的 inset 转为根容器 padding，避免 UI 顶到状态栏下
        val root = findViewById<android.view.View>(R.id.rootContainer)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

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
        findViewById<Button>(R.id.btnScanQr).setOnClickListener { startQrScan() }
        findViewById<Button>(R.id.btnSendFile).setOnClickListener { pickAndSendFile() }

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
        // 处理从其他 app 分享的文件（系统分享菜单 → clipd）
        handleShareIntent(intent)

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
        updateNotifListenerStatus()
        checkPromotedNotifPermission()
    }

    @Suppress("NewApi")
    private fun checkPromotedNotifPermission() {
        if (Build.VERSION.SDK_INT < 36) return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!nm.canPostPromotedNotifications()) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean("promoted_notif_asked", false)) {
                prefs.edit().putBoolean("promoted_notif_asked", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("开启原子通知")
                    .setMessage("允许 clipd 显示实时活动通知（原子岛/灵动岛），在状态栏显示同步状态")
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            startActivity(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").apply {
                                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                            })
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            })
                        }
                    }
                    .setNegativeButton("跳过", null)
                    .show()
            }
        }
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
            val manuallyStopped = prefs.getBoolean("manually_stopped", false)
            // 只要 WiFi 可用且用户没有主动停止，就自动拉起同步
            if (isWifiConnected() && !manuallyStopped) {
                startSync()
                return
            }
            val reason = when {
                wasRunning -> "服务已停止（可能被系统回收）"
                manuallyStopped -> "已手动停止 · 点击开始恢复同步"
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
            updateNotifListenerStatus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        handleShareIntent(intent)
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
        applyPairingUri(uri.toString())
    }

    private fun applyPairingUri(raw: String): Boolean {
        val uri = try { android.net.Uri.parse(raw) } catch (_: Exception) { return false }
        if (uri.scheme != "clipd" || uri.host != "connect") {
            Toast.makeText(this, "无效的配对二维码", Toast.LENGTH_SHORT).show()
            return false
        }
        val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
        if (ip.isBlank()) {
            Toast.makeText(this, "二维码缺少 IP", Toast.LENGTH_SHORT).show()
            return false
        }
        Sync.saveUbuntuIp(this, ip)
        Sync.log("通过二维码配对: $ip")
        Toast.makeText(this, "已配对: $ip", Toast.LENGTH_SHORT).show()
        // 主动 ping 一次：桌面端 /ping 处理器会从 client_address 学习手机 IP
        Sync.executor.execute { Sync.isClipdServerPublic(ip) }
        if (!isRunning) startSync() else verifyAndConnect(ip)
        return true
    }

    private val pickFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) sendFileUris(uris)
    }

    private fun pickAndSendFile() {
        if (Sync.ubuntuIp.isNullOrBlank()) {
            Toast.makeText(this, "尚未连接到桌面", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            pickFileLauncher.launch("*/*")
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendFileUris(uris: List<android.net.Uri>) {
        val total = uris.size
        Toast.makeText(this, "开始发送 $total 个文件", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            var fail = 0
            for ((idx, uri) in uris.withIndex()) {
                val latch = java.util.concurrent.CountDownLatch(1)
                Sync.sendFile(applicationContext, uri) { success, msg ->
                    if (success) ok++ else fail++
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "(${idx + 1}/$total) $msg",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    latch.countDown()
                }
                latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    "发送完成: 成功 $ok / 失败 $fail",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun handleShareIntent(intent: Intent): Boolean {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                } ?: return false
                sendFileUris(listOf(uri))
                return true
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<android.net.Uri> = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                        ?: return false
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                        ?: return false
                }
                if (uris.isNotEmpty()) { sendFileUris(uris); return true }
                return false
            }
            else -> return false
        }
    }

    private fun startQrScan() {
        try {
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue ?: return@addOnSuccessListener
                    applyPairingUri(raw)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "扫码失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                .addOnCanceledListener { /* 用户取消 */ }
        } catch (e: Throwable) {
            Toast.makeText(this, "扫码不可用: ${e.message}", Toast.LENGTH_LONG).show()
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
        val a11y = isAccessibilityEnabled()
        val overlay = android.provider.Settings.canDrawOverlays(this)

        if (a11y && overlay) {
            tvAccessibility.text = "✓ 剪切板自动同步已启用"
            tvAccessibility.setTextColor(getColor(R.color.connected))
            btnAccessibility.text = "设置"
        } else if (!overlay) {
            tvAccessibility.text = "⚠ 需授予悬浮窗权限（剪切板同步必需）"
            tvAccessibility.setTextColor(getColor(R.color.warning))
            btnAccessibility.text = "前往授权"
            btnAccessibility.setOnClickListener {
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
            }
        } else {
            tvAccessibility.text = "⚠ 需开启辅助功能以检测复制操作"
            tvAccessibility.setTextColor(getColor(R.color.warning))
            btnAccessibility.text = "前往开启"
            btnAccessibility.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
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
                tvStatus.text = "本地监听中"
                tvStatus.setTextColor(getColor(R.color.warning))
                tvStatusSub.text = detail.ifBlank { "剪切板监听已启动，连接 WiFi 后可同步到 Ubuntu" }
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
        KeepAliveJobService.schedule(this)
        startForegroundService(Intent(this, ClipSyncService::class.java))
        isRunning = true
        btnToggle.text = "停止同步"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("started_once", true)
            .putBoolean("manually_stopped", false)
            .apply()
        if (!isWifiConnected()) {
            setState(AppState.NO_WIFI)
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("manually_stopped", true).apply()
        setState(AppState.STOPPED, "已手动停止")
    }

    // 本次进程内"跳过"标记 — 不持久化，下次冷启动会再次检查
    private var skipBattOptThisRun = false
    private var skipAutoStartThisRun = false

    private fun promptBackgroundSurvival() {
        if (skipBattOptThisRun) {
            promptAutoStart()
            return
        }
        val pm = runCatching { getSystemService(POWER_SERVICE) as android.os.PowerManager }.getOrNull()
        val needBattOpt = pm != null && !pm.isIgnoringBatteryOptimizations(packageName)
        if (!needBattOpt) {
            promptAutoStart()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("加入电量优化白名单")
            .setMessage("系统会在后台杀掉未加白的 app，导致 clipd 同步中断。\n下一步请点击「允许」。")
            .setCancelable(false)
            .setPositiveButton("去设置") { _, _ ->
                @Suppress("BatteryLife")
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }
                // onResume 会再次调用 promptBackgroundSurvival —— 没授成功会再问
            }
            .setNegativeButton("本次跳过") { _, _ ->
                skipBattOptThisRun = true
                promptAutoStart()
            }
            .show()
    }

    private fun promptAutoStart() {
        if (skipAutoStartThisRun) return
        // 自启动状态没有可用查询 API：用户明确点过"已设置"或"去设置"后就不再问
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("autostart_user_confirmed", false)) {
            skipAutoStartThisRun = true
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启后台自启动（vivo/OPPO/MIUI）")
            .setMessage("系统省电策略可能在锁屏后杀掉 clipd 后台服务。\n请在下一页找到 clipd，开启「允许自启动」/「后台运行」。\n如果已设置过，点「已设置」不再提示。")
            .setCancelable(false)
            .setPositiveButton("去设置") { _, _ ->
                openAutoStartSettings()
                skipAutoStartThisRun = true
            }
            .setNeutralButton("已设置") { _, _ ->
                prefs.edit().putBoolean("autostart_user_confirmed", true).apply()
                skipAutoStartThisRun = true
            }
            .setNegativeButton("本次跳过") { _, _ -> skipAutoStartThisRun = true }
            .show()
    }

    private fun openAutoStartSettings() {
        // 依次尝试各厂商自启动管理页面，失败回退到应用详情
        val candidates = listOf(
            // vivo
            ComponentName("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure",
                "com.iqoo.secure.safeguard.SoftPermissionDetailActivity"),
            ComponentName("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            // OPPO
            ComponentName("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Xiaomi
            ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Huawei
            ComponentName("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* 继续尝试下一个 */ }
        }
        // 全部失败：打开应用详情
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            Toast.makeText(this, "未找到自启动设置页，请手动前往系统设置", Toast.LENGTH_LONG).show()
        }
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
        // 串行化引导：电量优化 → 自启动 → 悬浮窗。
        // 实时检查实际状态而非一次性 flag；只有"用户明确跳过"才在本次会话内不再弹。
        promptBackgroundSurvival()
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
