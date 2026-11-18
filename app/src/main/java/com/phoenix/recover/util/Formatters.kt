package com.phoenix.recover.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i])
        else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun duration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun count(value: Int): String = String.format(Locale.US, "%,d", value)

    fun shortPath(path: String, max: Int = 46): String {
        if (path.length <= max) return path
        val keep = max - 1
        val tail = keep / 2
        val head = keep - tail
        return path.take(head) + "…" + path.takeLast(tail)
    }

    fun percent(part: Long, total: Long): Int {
        if (total <= 0) return 0
        return ((part.toDouble() / total) * 100).toInt().coerceIn(0, 100)
    }

    fun relativeTime(timestamp: Long): String {
        if (timestamp <= 0) return "recently"
        val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(timestamp))
        }
    }
}
