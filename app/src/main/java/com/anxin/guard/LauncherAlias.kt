package com.anxin.guard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * 桌面图标隐藏 / 显示管理。
 *
 * 桌面图标实际上是 Manifest 里的一个 LAUNCHER activity-alias（LauncherAlias）。
 * 通过 PackageManager.setComponentEnabledSetting 禁用 alias，
 * 图标就从桌面消失；重新启用就能恢复。
 *
 * 禁用 alias 之后：
 * - MainActivity 本身仍然是 enabled=true，可以被 adb am start 或显式 Intent 打开；
 * - GuardService、BootReceiver 等组件不受影响；
 * - 老人找不到图标就不会进入卸载流程。
 */
object LauncherAlias {

    private const val ALIAS_CLASS = "com.anxin.guard.LauncherAlias"

    private fun component(context: Context): ComponentName =
        ComponentName(context.packageName, ALIAS_CLASS)

    fun isHidden(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context))
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    /** 隐藏桌面图标（需要 ACTION_MAIN + LAUNCHER 都存在于 manifest） */
    fun hide(context: Context) {
        try {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (ignored: Throwable) {
        }
    }

    /** 恢复桌面图标 */
    fun show(context: Context) {
        try {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (ignored: Throwable) {
        }
    }

    /** 调试用：adb shell am broadcast -a com.anxin.guard.SHOW_LAUNCHER */
    const val ACTION_SHOW_LAUNCHER = "com.anxin.guard.SHOW_LAUNCHER"
    const val ACTION_HIDE_LAUNCHER = "com.anxin.guard.HIDE_LAUNCHER"

    /** 发一个空广播就能触发刷新 */
    fun debugRefresh(context: Context) {
        context.sendBroadcast(Intent("${context.packageName}.REFRESH"))
    }
}
