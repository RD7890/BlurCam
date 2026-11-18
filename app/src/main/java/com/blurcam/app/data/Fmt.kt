package com.blurcam.app.data

import java.util.Locale

/** 0:07 */
fun fmtClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** 0:07.4 */
fun fmtTenths(ms: Long): String {
    val m = ms.coerceAtLeast(0)
    val s = m / 1000
    return String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, (m % 1000) / 100)
}
