package com.anxin.guard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 核心状态机：只做"算"，不做界面操作。
 *
 * 一次 tick 的判定顺序：
 * 1. 跨天则清零今日统计；
 * 2. 关闭守护 -> 清零本轮；
 * 3. 休息中 -> 保持锁定，时间到则解锁并开始新一轮；
 * 4. 正常使用 -> 累加亮屏时长；
 * 5. 达到上限 -> 进入休息，由服务负责锁屏。
 */
class GuardEngine(context: Context) {

    private val prefs = Prefs(context.applicationContext)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    data class Snapshot(
        val enabled: Boolean,
        val sessionSeconds: Long,
        val limitSeconds: Long,
        val todaySeconds: Long,
        val restricted: Boolean,
        val restrictedRemainMs: Long
    )

    data class TickResult(
        val snapshot: Snapshot,
        /** 本次 tick 是否刚进入休息，用于立即锁屏 */
        val justRestricted: Boolean
    )

    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot {
        val until = prefs.restrictedUntil
        val restricted = until > now
        return Snapshot(
            enabled = prefs.enabled,
            sessionSeconds = prefs.sessionSeconds,
            limitSeconds = prefs.sessionLimitMin * 60L,
            todaySeconds = prefs.todaySeconds,
            restricted = restricted,
            restrictedRemainMs = if (restricted) until - now else 0L
        )
    }

    fun tick(now: Long, screenOn: Boolean, inCall: Boolean): TickResult {
        rolloverDay(now)

        if (!prefs.enabled) {
            prefs.lastTickAt = now
            if (prefs.sessionSeconds != 0L) prefs.sessionSeconds = 0L
            if (prefs.restrictedUntil != 0L) prefs.restrictedUntil = 0L
            return TickResult(snapshot(now), false)
        }

        // 远程临时解锁生效中：直接解除已有锁定，并且不因超时再次进入休息
        val remoteUnlocked = prefs.remoteUnlockUntil > now
        if (remoteUnlocked && prefs.restrictedUntil != 0L) {
            prefs.restrictedUntil = 0L
            prefs.sessionSeconds = 0L
        }

        // 休息进行中：不计时，等待倒计时结束
        if (prefs.restrictedUntil > now) {
            prefs.lastTickAt = now
            return TickResult(snapshot(now), false)
        }

        // 休息刚结束：解锁并重新开始一轮
        if (prefs.restrictedUntil != 0L) {
            prefs.restrictedUntil = 0L
            prefs.sessionSeconds = 0L
        }

        val last = prefs.lastTickAt
        prefs.lastTickAt = now
        if (screenOn && !inCall && last > 0L) {
            val delta = ((now - last) / 1000L).coerceIn(0L, MAX_TICK_GAP_SECONDS)
            if (delta > 0L) {
                prefs.sessionSeconds = prefs.sessionSeconds + delta
                prefs.todaySeconds = prefs.todaySeconds + delta
            }
        }

        if (prefs.sessionSeconds >= prefs.sessionLimitMin * 60L && !remoteUnlocked) {
            prefs.restrictedUntil = now + prefs.cooldownMin * 60_000L
            return TickResult(snapshot(now), true)
        }

        return TickResult(snapshot(now), false)
    }

    private fun rolloverDay(now: Long) {
        val today = dayFormat.format(Date(now))
        if (prefs.todayDate != today) {
            prefs.todayDate = today
            prefs.todaySeconds = 0L
        }
    }

    companion object {
        private const val MAX_TICK_GAP_SECONDS = 30L
    }
}
