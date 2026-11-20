package com.anxin.guard

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings

/**
 * 记录本应用是否在前台。
 *
 * 休息期间用户点通知进来查看剩余时间时，不应该立刻被锁屏；
 * 一旦离开本应用，就恢复强制锁屏。
 */
object AppVisibility {

    @Volatile
    var startedActivities: Int = 0
        private set

    val isForeground: Boolean
        get() = startedActivities > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * "使用情况访问"权限的封装。
 *
 * 有这个权限，就能知道当前前台是哪个应用，
 * 从而把紧急拨号的放行范围精确限制在拨号/通话界面，而不是整台手机。
 * 没有权限时退化为"只给一小段拨号时间"。
 */
object UsageAccess {

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
        } catch (ignored: Throwable) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Throwable) {
        }
    }

    /** 最近一次切到前台的包名；无权限时返回 null */
    fun foregroundPackage(context: Context): String? {
        if (!isGranted(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        return try {
            val events = manager.queryEvents(now - 60_000L, now)
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = event.packageName
                }
            }
            last
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 拨号、通话、联系人界面都属于"允许放行"的范围 */
    fun isPhoneApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName == "com.anxin.guard") return true
        if (PHONE_PACKAGES.contains(packageName)) return true
        return packageName.contains("dialer") ||
            packageName.contains("incallui") ||
            packageName.contains("contacts") ||
            packageName == "com.android.phone" ||
            packageName.startsWith("com.android.server.telecom")
    }

    private val PHONE_PACKAGES = setOf(
        "com.android.dialer",
        "com.android.contacts",
        "com.android.phone",
        "com.huawei.contacts",
        "com.huawei.dialer",
        "com.huawei.phone"
    )
}
