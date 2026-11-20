package com.anxin.guard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var engine: GuardEngine

    private lateinit var stateValue: TextView
    private lateinit var todayValue: TextView
    private lateinit var sessionValue: TextView
    private lateinit var sessionProgress: ProgressBar
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var seekLimit: SeekBar
    private lateinit var valueLimit: TextView
    private lateinit var seekCooldown: SeekBar
    private lateinit var valueCooldown: TextView
    private lateinit var btnAdmin: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnNotify: TextView
    private lateinit var btnBattery: TextView
    private lateinit var btnUsage: TextView
    private lateinit var btnHideLauncher: TextView
    private lateinit var remoteValue: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var updating = false

    private val refreshLoop = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000L)
        }
    }

    private val notifyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        engine = GuardEngine(this)

        bindViews()
        setupSettings()
        setupActions()

        // 首次打开即开始守护
        if (prefs.enabled) GuardService.start(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    private fun bindViews() {
        stateValue = findViewById(R.id.state_value)
        todayValue = findViewById(R.id.today_value)
        sessionValue = findViewById(R.id.session_value)
        sessionProgress = findViewById(R.id.session_progress)
        switchEnabled = findViewById(R.id.switch_enabled)
        seekLimit = findViewById(R.id.seek_limit)
        valueLimit = findViewById(R.id.value_limit)
        seekCooldown = findViewById(R.id.seek_cooldown)
        valueCooldown = findViewById(R.id.value_cooldown)
        btnAdmin = findViewById(R.id.btn_admin)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnNotify = findViewById(R.id.btn_notify)
        btnBattery = findViewById(R.id.btn_battery)
        btnUsage = findViewById(R.id.btn_usage)
        btnHideLauncher = findViewById(R.id.btn_hide_launcher)
        remoteValue = findViewById(R.id.remote_value)
    }

    private fun setupSettings() {
        // 程序初始化滑块时会触发回调，期间禁止回写配置
        updating = true
        seekLimit.max = (LIMIT_MAX - LIMIT_MIN) / LIMIT_STEP
        seekCooldown.max = (COOLDOWN_MAX - COOLDOWN_MIN) / COOLDOWN_STEP

        seekLimit.setOnSeekBarChangeListener(SeekListener(
            isUpdating = { updating },
            onChanged = { progress ->
                prefs.sessionLimitMin = LIMIT_MIN + progress * LIMIT_STEP
                render()
            }
        ))
        seekCooldown.setOnSeekBarChangeListener(SeekListener(
            isUpdating = { updating },
            onChanged = { progress ->
                prefs.cooldownMin = COOLDOWN_MIN + progress * COOLDOWN_STEP
                render()
            }
        ))
        updating = false

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            prefs.enabled = checked
            if (checked) {
                prefs.resetSession()
                GuardService.start(this)
            } else {
                GuardService.stop(this)
            }
            render()
        }
    }

    private fun setupActions() {
        btnAdmin.setOnClickListener {
            if (GuardDeviceAdmin.isActive(this)) {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (ignored: Throwable) {
                }
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        GuardDeviceAdmin.component(this)
                    )
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "用于在达到使用上限后自动锁屏。不会清除数据，也不会影响通话和短信。"
                    )
                try {
                    startActivity(intent)
                } catch (ignored: Throwable) {
                }
            }
        }

        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (ignored: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                } catch (ignoredToo: Throwable) {
                }
            }
        }

        btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        }

        btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Throwable) {
            }
        }

        btnUsage.setOnClickListener {
            UsageAccess.openSettings(this)
        }

        btnHideLauncher.setOnClickListener {
            // 确认对话框：一旦隐藏，桌面不会有图标
            android.app.AlertDialog.Builder(this)
                .setTitle("隐藏桌面图标")
                .setMessage(
                    "隐藏后图标会从桌面消失，守护功能继续在后台运行。\n\n" +
                        "想恢复时，我可以通过电脑用 adb 帮你找回，" +
                        "也可以在设置里重新启用组件。\n\n" +
                        "现在就隐藏吗？"
                )
                .setPositiveButton("隐藏") { _, _ ->
                    LauncherAlias.hide(this)
                    prefs.launcherVisible = false
                    btnHideLauncher.text = "图标已隐藏"
                    btnHideLauncher.isEnabled = false
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun render() {
        updating = true
        val snapshot = engine.snapshot()

        switchEnabled.isChecked = snapshot.enabled
        todayValue.text = "${snapshot.todaySeconds / 60} 分钟"

        val limitMin = snapshot.limitSeconds / 60
        sessionValue.text =
            "本次连续使用 ${snapshot.sessionSeconds / 60} 分钟 / 上限 $limitMin 分钟"

        sessionProgress.max = 1000
        sessionProgress.progress = if (snapshot.limitSeconds <= 0L) {
            0
        } else {
            ((snapshot.sessionSeconds * 1000L) / snapshot.limitSeconds).toInt().coerceIn(0, 1000)
        }

        stateValue.text = when {
            !snapshot.enabled -> "守护已关闭"
            snapshot.restricted -> "休息锁定中 · 还需 ${formatCountdown(snapshot.restrictedRemainMs)}"
            else -> {
                val remain =
                    (snapshot.limitSeconds - snapshot.sessionSeconds).coerceAtLeast(0L) / 60L
                "守护中 · 还可使用 $remain 分钟"
            }
        }

        valueLimit.text = "${prefs.sessionLimitMin} 分钟"
        valueCooldown.text = "${prefs.cooldownMin} 分钟"

        seekLimit.progress = (prefs.sessionLimitMin - LIMIT_MIN) / LIMIT_STEP
        seekCooldown.progress = (prefs.cooldownMin - COOLDOWN_MIN) / COOLDOWN_STEP

        btnAdmin.text = if (GuardDeviceAdmin.isActive(this)) {
            "设备管理器：已激活"
        } else {
            "激活设备管理器（重要）"
        }
        btnOverlay.text = if (Settings.canDrawOverlays(this)) {
            "悬浮窗提醒：已开启"
        } else {
            "开启悬浮窗提醒（重要）"
        }
        btnNotify.text = if (notificationsEnabled()) "通知：已开启" else "开启通知"
        btnBattery.text = if (isIgnoringBattery()) "电池优化：已忽略" else "忽略电池优化"
        btnUsage.text = if (UsageAccess.isGranted(this)) {
            "使用情况访问：已开启"
        } else {
            "开启使用情况访问（推荐）"
        }

        if (LauncherAlias.isHidden(this)) {
            btnHideLauncher.text = "图标已隐藏"
            btnHideLauncher.isEnabled = false
        } else {
            btnHideLauncher.text = "隐藏桌面图标"
            btnHideLauncher.isEnabled = true
        }

        remoteValue.text = buildRemoteHint()

        updating = false
    }

    private fun buildRemoteHint(): String {
        val ip = localWifiIp()
        val key = prefs.remoteKey
        return if (ip == null) {
            "当前没有连接到 WiFi，请先让手机接入家里的 WiFi。\n密钥：$key"
        } else {
            "http://$ip:${RemoteControlServer.PORT}/?key=$key"
        }
    }

    private fun localWifiIp(): String? {
        return try {
            for (network in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp || network.isLoopback) continue
                for (address in Collections.list(network.inetAddresses)) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (ignored: Throwable) {
            null
        }
    }
    private fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun isIgnoringBattery(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (ignored: Throwable) {
        }
    }

    private class SeekListener(
        private val isUpdating: () -> Boolean,
        private val onChanged: (Int) -> Unit
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser || isUpdating()) return
            onChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        private const val LIMIT_MIN = 10
        private const val LIMIT_MAX = 120
        private const val LIMIT_STEP = 5

        private const val COOLDOWN_MIN = 5
        private const val COOLDOWN_MAX = 60
        private const val COOLDOWN_STEP = 5
    }
}
