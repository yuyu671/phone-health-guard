package com.anxin.guard

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 常驻前台服务：唯一负责"驱动"状态机的地方。
 *
 * - 每 5 秒 tick 一次（休息中 1 秒，用于倒计时与反复锁屏）；
 * - 屏幕熄灭时不计时；
 * - 休息期间只要用户解锁，就立即再次锁屏，直到倒计时结束；
 * - 通话中（含来电响铃）暂停计时、暂停锁屏、隐藏全屏提醒；
 * - 本应用界面在前台时暂不锁屏，方便查看剩余时间。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var engine: GuardEngine
    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var remote: RemoteControlServer

    private var screenOn = true
    private var lastNotificationKey: String? = null
    private var alertShown = false
    private var overlaySuppressedUntil = 0L
    private var dialGraceUntil = 0L
    /** 紧急拨号放行中：停留在拨号界面期间不限时，切走立即恢复锁定 */
    private var dialGraceActive = false
    private var unlockGraceUntil = 0L
    private var unlockGraceUsed = false
    private var lastLockRequestAt = 0L
    private var lastRemoteCheckAt = 0L
    private var lastKeyguardLocked = false
    private var currentKeyguardLocked = false

    private val boundaryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    unlockGraceUntil = 0L
                }
                // 解锁宽限的真正生效靠 step() 里检测 keyguard 状态转换，
                // 这里仅兜底设一次 screenOn
                Intent.ACTION_USER_PRESENT -> screenOn = true
            }
            step()
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            step()
            val restricted = engine.snapshot().restricted
            handler.postDelayed(this, if (restricted) 1000L else 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = GuardEngine(this)
        prefs = Prefs(this)
        overlay = OverlayController(this) {
            // 紧急拨号：只放行拨号与通话界面，停留多久都不限时
            val now = System.currentTimeMillis()
            dialGraceActive = true
            dialGraceUntil = now + DIAL_WINDOW_MS
            overlaySuppressedUntil = dialGraceUntil
            overlay.hide()
        }

        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        Notifications.ensureChannels(this)

        ContextCompat.registerReceiver(
            this,
            boundaryReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        startAsForeground()
        remote = RemoteControlServer(this, prefs, engine) {
            handler.post { step() }
        }
        remote.start()
        BootReceiver.schedule(this)
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(loop)
        handler.post(loop)
        BootReceiver.schedule(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(boundaryReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
        overlay.hide()
        if (::remote.isInitialized) remote.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val inCall = isInCall()
        currentKeyguardLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .isKeyguardLocked

        // 检测"刚解锁"：上一次还锁着、这次没锁 → 给解锁缓冲
        // 不依赖 USER_PRESENT 广播，避免广播延迟导致刚解锁就被锁回去
        if (lastKeyguardLocked && !currentKeyguardLocked) {
            val grace = if (!unlockGraceUsed) {
                unlockGraceUsed = true
                UNLOCK_GRACE_FIRST_MS
            } else {
                UNLOCK_GRACE_REPEAT_MS
            }
            unlockGraceUntil = now + grace
        }
        lastKeyguardLocked = currentKeyguardLocked

        val result = engine.tick(now, screenOn, inCall)
        val snapshot = result.snapshot

        // 休息结束，重置"首次解锁缓冲"标记，下一轮重新给长缓冲
        if (!snapshot.restricted) {
            unlockGraceUsed = false
            dialGraceActive = false
        }

        // 远程控制端口自愈：绑定失败或线程异常退出后，每 30 秒重试一次
        if (now - lastRemoteCheckAt > 30_000L) {
            lastRemoteCheckAt = now
            if (!remote.isRunning()) remote.start()
        }

        publishStatus(snapshot)
        publishOverlay(snapshot, inCall)
        enforceLock(snapshot, inCall)
    }

    private fun startAsForeground() {
        try {
            val notification = Notifications.status(this, "守护已启动", "正在统计使用时间")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifications.ID_STATUS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifications.ID_STATUS, notification)
            }
        } catch (ignored: Throwable) {
            // 个别 ROM 会拒绝前台服务或通知，这里兜住，避免整个应用闪退
        }
    }

    private fun publishStatus(snapshot: GuardEngine.Snapshot) {
        val title = when {
            !snapshot.enabled -> "守护已关闭"
            snapshot.restricted -> "运行内存不足 · 正在清理"
            else -> {
                val remainMin =
                    (snapshot.limitSeconds - snapshot.sessionSeconds).coerceAtLeast(0L) / 60L
                "已用 ${snapshot.sessionSeconds / 60} 分钟 · 还可使用 $remainMin 分钟"
            }
        }
        val text = "今日 ${snapshot.todaySeconds / 60} 分钟"
        val key = "$title|$text"
        if (key != lastNotificationKey) {
            lastNotificationKey = key
            Notifications.notifyStatus(this, title, text)
        }
    }

    private fun publishOverlay(snapshot: GuardEngine.Snapshot, inCall: Boolean) {
        val canOverlay = Settings.canDrawOverlays(this)
        val now = System.currentTimeMillis()
        val inDialGrace = dialGraceActive || now < dialGraceUntil
        val visible = snapshot.restricted && screenOn && !inCall && !currentKeyguardLocked &&
            canOverlay && now >= overlaySuppressedUntil && !inDialGrace

        if (visible) {
            overlay.show(snapshot.restrictedRemainMs)
        } else {
            overlay.hide()
        }

        // 没有悬浮窗权限时，退化成一条高优先级通知
        if (snapshot.restricted && !visible && !inCall && !canOverlay && !alertShown) {
            alertShown = true
            Notifications.notifyAlert(
                this,
                "手机运行内存不足",
                "后台程序较多，正在自动清理，约 ${prefs.cooldownMin} 分钟后恢复"
            )
        }

        if (!snapshot.restricted && alertShown) {
            alertShown = false
            Notifications.notifyAlert(this, "清理完成", "手机运行已恢复正常")
        }
    }

    /**
     * 休息期间的强制锁屏：
     * 用户每次解锁都会触发 USER_PRESENT，下一次 tick 会立刻再锁回去，
     * 直到 restrictedUntil 到点为止。
     */
    private fun enforceLock(snapshot: GuardEngine.Snapshot, inCall: Boolean) {
        if (!snapshot.restricted) return
        val now = System.currentTimeMillis()
        if (inCall) {
            // 通话期间持续续期，挂断后还有一小段缓冲
            if (dialGraceActive) {
                dialGraceUntil = now + DIAL_TAIL_MS
            }
            return
        }
        if (dialGraceActive) {
            // 有"使用情况访问"权限时，停留在拨号/通话/联系人界面就一直放行，不限时
            if (UsageAccess.isGranted(this)) {
                if (UsageAccess.isPhoneApp(UsageAccess.foregroundPackage(this))) return
                // 切到别的 App：立即取消放行并恢复锁定
                dialGraceActive = false
                dialGraceUntil = 0L
                overlaySuppressedUntil = 0L
            } else if (now < dialGraceUntil) {
                // 没有权限时无法判断前台，只能给一小段时间
                return
            } else {
                dialGraceActive = false
            }
        }
        if (now < unlockGraceUntil) return
        if (AppVisibility.isForeground) return
        if (!GuardDeviceAdmin.isActive(this)) return

        if (currentKeyguardLocked) return

        if (now - lastLockRequestAt < LOCK_RETRY_INTERVAL_MS) return
        lastLockRequestAt = now
        GuardDeviceAdmin.lock(this)
    }

    private fun isInCall(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (audioManager.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }

    companion object {
        private const val LOCK_RETRY_INTERVAL_MS = 1500L
        /** 紧急拨号窗口：够老人找到号码并拨出，超时就恢复锁定 */
        private const val DIAL_WINDOW_MS = 45_000L
        /** 通话结束后的缓冲，避免刚挂断就被立刻锁回去 */
        private const val DIAL_TAIL_MS = 20_000L
        /** 每次解锁后的缓冲期：让用户有机会看到"紧急拨号"按钮 */
        private const val UNLOCK_GRACE_FIRST_MS = 20_000L // 本轮首次解锁：20 秒
        private const val UNLOCK_GRACE_REPEAT_MS = 8_000L // 反复解锁：8 秒（够点按钮，不够刷视频）

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GuardService::class.java)
                )
            } catch (ignored: Throwable) {
                // 后台启动受限时忽略，用户下次打开应用会重新启动
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
