package com.anxin.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 守护服务的看门狗。
 *
 * 负责三种"服务被系统干掉后自动复活"的场景：
 * - 开机 / 快速重启；
 * - 应用被覆盖安装（升级后服务不会自己回来）；
 * - 定时心跳：每 3 分钟检查一次，服务没在跑就拉起来。
 *
 * 唯一无法自救的情况是用户主动"强制停止"应用（stopped=true），
 * 此时系统会屏蔽所有闹钟和广播，只能重新打开应用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_WATCHDOG -> {
                if (Prefs(context).enabled) GuardService.start(context)
                schedule(context)
            }
        }
    }

    companion object {

        const val ACTION_WATCHDOG = "com.anxin.guard.WATCHDOG"

        /** 心跳间隔：服务被杀后最多这么久就会恢复 */
        private const val INTERVAL_MS = 3 * 60 * 1000L
        private const val REQUEST_CODE = 1001

        /** 安排下一次心跳；由服务和本接收器共同调用 */
        fun schedule(context: Context) {
            val manager =
                context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (ignored: Throwable) {
                try {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } catch (ignoredToo: Throwable) {
                }
            }
        }
    }
}
