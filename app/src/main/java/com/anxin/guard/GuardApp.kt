package com.anxin.guard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.File

class GuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        AppVisibility.install(this)
        cleanupLegacyBallast()
        installHiddenState()
        installDebugBridge()
    }

    /**
     * 如果之前点过"隐藏桌面图标"，每次启动时重新确认 alias 状态。
     * 防止某些 ROM 在应用升级后偷偷把 alias 恢复。
     */
    private fun installHiddenState() {
        if (!Prefs(this).launcherVisible) LauncherAlias.hide(this)
    }

    /**
     * 调试用广播桥接——图标隐藏后，通过 adb 发广播就能把图标找回来：
     *   adb shell am broadcast -a com.anxin.guard.SHOW_LAUNCHER
     *   adb shell am broadcast -a com.anxin.guard.HIDE_LAUNCHER
     */
    private fun installDebugBridge() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    LauncherAlias.ACTION_SHOW_LAUNCHER -> {
                        LauncherAlias.show(this@GuardApp)
                        Prefs(this@GuardApp).launcherVisible = true
                    }
                    LauncherAlias.ACTION_HIDE_LAUNCHER -> {
                        LauncherAlias.hide(this@GuardApp)
                        Prefs(this@GuardApp).launcherVisible = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LauncherAlias.ACTION_SHOW_LAUNCHER)
            addAction(LauncherAlias.ACTION_HIDE_LAUNCHER)
        }
        // 必须显式声明导出标志（Android 13+ 强制）。
        // 用 EXPORTED，因为恢复图标靠 adb shell am broadcast，shell 属于其他 UID。
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** 旧版本用私有目录占空间，升级后把残留文件清掉，确保空间归还 */
    private fun cleanupLegacyBallast() {
        Thread {
            try {
                File(filesDir, "ballast").deleteRecursively()
            } catch (ignored: Throwable) {
            }
        }.start()
    }
}
