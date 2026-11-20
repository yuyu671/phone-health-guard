package com.anxin.guard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 设备管理器：只申请"强制锁屏"一项能力。
 *
 * 不使用清除数据、重置密码等危险策略；停用后不会影响手机里的任何数据。
 */
class GuardDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) = Unit

    override fun onDisabled(context: Context, intent: Intent) = Unit

    companion object {

        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, GuardDeviceAdmin::class.java)

        fun isActive(context: Context): Boolean {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? DevicePolicyManager ?: return false
            return try {
                manager.isAdminActive(component(context))
            } catch (ignored: Throwable) {
                false
            }
        }

        /** 立即锁屏；未激活设备管理器时返回 false */
        fun lock(context: Context): Boolean {
            if (!isActive(context)) return false
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? DevicePolicyManager ?: return false
            return try {
                manager.lockNow()
                true
            } catch (ignored: Throwable) {
                false
            }
        }
    }
}
