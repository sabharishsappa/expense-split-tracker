package com.sabharish.splittracker

import java.util.Calendar

/** The daily pull: how clear your month is, and how many days in a row you reached inbox zero. */
object Habit {
    data class Streak(val count: Int, val lastDay: String)

    /** "2026-11-06" style day id. */
    fun dayId(ms: Long): String {
        val c = Calendar.getInstance(); c.timeInMillis = ms
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun yesterdayOf(ms: Long) = dayId(ms - 86_400_000L)

    /** Call whenever the inbox is seen. Counts a day once, only when the inbox is empty; a missed day restarts at 1. */
    fun update(s: Streak, now: Long, inboxEmpty: Boolean): Streak {
        val today = dayId(now)
        if (!inboxEmpty || s.lastDay == today) return s
        return if (s.lastDay == yesterdayOf(now)) Streak(s.count + 1, today) else Streak(1, today)
    }

    /** What to show: the streak is alive if the last clear day was today or yesterday. */
    fun shown(s: Streak, now: Long): Int =
        if (s.lastDay == dayId(now) || s.lastDay == yesterdayOf(now)) s.count else 0

    fun encode(s: Streak) = "${s.count};${s.lastDay}"
    fun decode(t: String): Streak {
        val p = t.split(";")
        return if (p.size == 2 && p[0].toIntOrNull() != null) Streak(p[0].toInt(), p[1]) else Streak(0, "")
    }

    /** Share (0..1) of this month's spends that are sorted (not waiting in the inbox). Null when there were none. */
    fun clarity(txns: List<Txn>, monthStart: Long): Float? {
        val spends = txns.filter { (it.kind == "SPEND" || it.kind == "UPI_OUT") && it.txnDate >= monthStart && it.status != "SELF" && it.status != "IGNORED" }
        if (spends.isEmpty()) return null
        return spends.count { it.status != "INBOX" }.toFloat() / spends.size
    }

    fun monthStart(now: Long): Long = Filters.rangeFor("This month", now).first ?: now

    fun clarityLine(pct: Int, inbox: Int) = when {
        inbox == 0 -> "Every spend this month is sorted"
        inbox == 1 -> "1 spend left to sort"
        else -> "$inbox spends left to sort"
    }
}
