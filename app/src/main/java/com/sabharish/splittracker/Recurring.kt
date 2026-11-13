package com.sabharish.splittracker

import java.util.Calendar

/** An entry that repeats every month (salary, rent, a subscription). Kept in a setting on the phone, so a backup carries it too. */
data class Recurring(
    val id: String, val title: String, val amount: Double, val kind: String,   // Expense | Income
    val category: String, val account: String, val day: Int, val last: String   // last = "yyyy-MM" of the month already created
)

object Recurrings {
    const val KEY = "recurring"

    fun monthKey(y: Int, m0: Int) = "%04d-%02d".format(y, m0 + 1)

    fun encode(l: List<Recurring>) = l.joinToString("\n") {
        listOf(it.id, it.title.replace(Regex("[\t\n]"), " "), it.amount, it.kind, it.category, it.account, it.day, it.last).joinToString("\t")
    }

    fun decode(s: String): List<Recurring> = s.lines().mapNotNull { line ->
        val p = line.split("\t"); if (p.size < 8) return@mapNotNull null
        Recurring(p[0], p[1], p[2].toDoubleOrNull() ?: return@mapNotNull null, p[3], p[4], p[5], p[6].toIntOrNull() ?: return@mapNotNull null, p[7])
    }

    /** A new repeat starts this month only if its day has not passed yet; it never back-fills the past. */
    fun startLast(day: Int, now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val (y, m) = if (day <= c.get(Calendar.DAY_OF_MONTH)) c.get(Calendar.YEAR) to c.get(Calendar.MONTH) else Stats.shift(c.get(Calendar.YEAR), c.get(Calendar.MONTH), -1)
        return monthKey(y, m)
    }

    /** Each (item, moment) that should exist by [now] and does not yet. At most 6 months back. */
    fun due(items: List<Recurring>, now: Long): List<Pair<Recurring, Long>> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val cy = c.get(Calendar.YEAR); val cm = c.get(Calendar.MONTH)
        val out = mutableListOf<Pair<Recurring, Long>>()
        for (r in items) {
            for (back in 5 downTo 0) {
                val (y, m) = Stats.shift(cy, cm, -back)
                if (monthKey(y, m) <= r.last) continue
                val cal = Calendar.getInstance().apply { clear(); set(y, m, 1) }
                val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(y, m, minOf(r.day, dim), 9, 0, 0)
                if (cal.timeInMillis <= now) out += r to cal.timeInMillis
            }
        }
        return out
    }
}
