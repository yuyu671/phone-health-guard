package com.anxin.guard

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置与运行状态存储。
 * 使用单进程 SharedPreferences，读写频率不高（每几秒一次），足够可靠。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 守护总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 单次连续使用上限（分钟） */
    var sessionLimitMin: Int
        get() = sp.getInt(KEY_LIMIT_MIN, DEFAULT_LIMIT_MIN)
        set(value) = sp.edit().putInt(KEY_LIMIT_MIN, value).apply()

    /** 休息时长（分钟），期间屏幕保持锁定 */
    var cooldownMin: Int
        get() = sp.getInt(KEY_COOLDOWN_MIN, DEFAULT_COOLDOWN_MIN)
        set(value) = sp.edit().putInt(KEY_COOLDOWN_MIN, value).apply()

    /** 本次连续使用秒数 */
    var sessionSeconds: Long
        get() = sp.getLong(KEY_SESSION_SECONDS, 0L)
        set(value) = sp.edit().putLong(KEY_SESSION_SECONDS, value).apply()

    /** 今日累计秒数（仅用于展示） */
    var todaySeconds: Long
        get() = sp.getLong(KEY_TODAY_SECONDS, 0L)
        set(value) = sp.edit().putLong(KEY_TODAY_SECONDS, value).apply()

    /** 今日日期，用于跨天清零 */
    var todayDate: String?
        get() = sp.getString(KEY_TODAY_DATE, null)
        set(value) = sp.edit().putString(KEY_TODAY_DATE, value).apply()

    /** 休息结束时间戳（毫秒），0 表示当前不在休息中 */
    var restrictedUntil: Long
        get() = sp.getLong(KEY_RESTRICTED_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_RESTRICTED_UNTIL, value).apply()

    /** 上一次统计时间戳（毫秒） */
    var lastTickAt: Long
        get() = sp.getLong(KEY_LAST_TICK_AT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_TICK_AT, value).apply()

    /** 桌面图标是否可见：false = 已隐藏，老人找不到 */
    var launcherVisible: Boolean
        get() = sp.getBoolean(KEY_LAUNCHER_VISIBLE, true)
        set(value) = sp.edit().putBoolean(KEY_LAUNCHER_VISIBLE, value).apply()

    /** 局域网远程控制密钥，首次访问时随机生成 */
    var remoteKey: String
        get() {
            val saved = sp.getString(KEY_REMOTE_KEY, null)
            if (!saved.isNullOrBlank()) return saved
            val generated = (100000..999999).random().toString()
            sp.edit().putString(KEY_REMOTE_KEY, generated).apply()
            return generated
        }
        set(value) = sp.edit().putString(KEY_REMOTE_KEY, value).apply()

    /** 远程临时解锁截止时间（毫秒），0 表示无远程解锁 */
    var remoteUnlockUntil: Long
        get() = sp.getLong(KEY_REMOTE_UNLOCK_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_REMOTE_UNLOCK_UNTIL, value).apply()

    /** 立即结束本次休息并重新计时 */
    fun resetSession() {
        val now = System.currentTimeMillis()
        sp.edit()
            .putLong(KEY_SESSION_SECONDS, 0L)
            .putLong(KEY_RESTRICTED_UNTIL, 0L)
            .putLong(KEY_LAST_TICK_AT, now)
            .apply()
    }

    companion object {
        private const val FILE = "guard_prefs"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_LIMIT_MIN = "limit_min"
        private const val KEY_COOLDOWN_MIN = "cooldown_min"
        private const val KEY_SESSION_SECONDS = "session_seconds"
        private const val KEY_TODAY_SECONDS = "today_seconds"
        private const val KEY_TODAY_DATE = "today_date"
        private const val KEY_RESTRICTED_UNTIL = "restricted_until"
        private const val KEY_LAST_TICK_AT = "last_tick_at"
        private const val KEY_LAUNCHER_VISIBLE = "launcher_visible"
        private const val KEY_REMOTE_KEY = "remote_key"
        private const val KEY_REMOTE_UNLOCK_UNTIL = "remote_unlock_until"

        const val DEFAULT_LIMIT_MIN = 40
        const val DEFAULT_COOLDOWN_MIN = 15
    }
}
