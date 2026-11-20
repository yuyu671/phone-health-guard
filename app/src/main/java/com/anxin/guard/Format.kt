package com.anxin.guard

import java.util.Locale

/** 毫秒 -> mm:ss */
fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
