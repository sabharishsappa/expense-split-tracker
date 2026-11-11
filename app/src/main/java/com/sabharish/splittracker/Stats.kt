package com.sabharish.splittracker

import java.util.Calendar
import java.util.Locale

/**
 * Numbers behind the Stats page, the calendar and the budgets. Pure and unit-tested.
 *
 * "Expense" is what you really spent: your own share of a tagged spend. A spend still waiting in the Inbox
 * counts in full for now (provisional), so a month never looks emptier than it is.
 */
object Stats {
    data class DayTotal(val spent: Double, val income: Double)

    private val SKIP = setOf("SELF", "IGNORED", "REVERSED", "FAILED", "REFUND", "BILLPAY")

    fun isSpend(t: Txn) = (t.kind == "SPEND" || t.kind == "UPI_OUT") && t.status !in SKIP

    /** Your expense for one transaction. */
    fun expenseOf(t: Txn, shares: List<Share>): Double {
        if (!isSpend(t)) return 0.0
        return if (t.status == "TAGGED") shares.filter { it.person == Logic.ME }.sumOf { it.amount } else t.amount
    }

    /** Money in that you called income (salary, gift, other). Repayments, borrowings and refunds are not income. */
    fun incomeOf(t: Txn): Double =
        if (t.kind == "UPI_IN" && t.status == "RECEIVED" && t.note.startsWith("Received:")) t.amount else 0.0

    fun monthOf(ms: Long, cal: Calendar = Calendar.getInstance()): Pair<Int, Int> {
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
    }

    /** (year, month0) moved by [delta] months. */
    fun shift(year: Int, month0: Int, delta: Int): Pair<Int, Int> {
        val n = year * 12 + month0 + delta
        return Math.floorDiv(n, 12) to Math.floorMod(n, 12)
    }

    fun inMonth(t: Txn, year: Int, month0: Int) = monthOf(t.txnDate) == (year to month0)

    fun spentInMonth(txns: List<Txn>, shares: Map<Long, List<Share>>, year: Int, month0: Int): Double =
        txns.filter { inMonth(it, year, month0) }.sumOf { expenseOf(it, shares[it.id] ?: emptyList()) }

    fun incomeInMonth(txns: List<Txn>, year: Int, month0: Int): Double =
        txns.filter { inMonth(it, year, month0) }.sumOf { incomeOf(it) }

    /** Spent and income for each day (1..31) of the month. */
    fun dayTotals(txns: List<Txn>, shares: Map<Long, List<Share>>, year: Int, month0: Int): Map<Int, DayTotal> {
        val cal = Calendar.getInstance()
        val out = HashMap<Int, DayTotal>()
        for (t in txns) {
            if (!inMonth(t, year, month0)) continue
            val s = expenseOf(t, shares[t.id] ?: emptyList()); val i = incomeOf(t)
            if (s == 0.0 && i == 0.0) continue
            cal.timeInMillis = t.txnDate
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val old = out[d] ?: DayTotal(0.0, 0.0)
            out[d] = DayTotal(old.spent + s, old.income + i)
        }
        return out
    }

    /** Spent per tag in the month, biggest first; spends without a tag are "Untagged". */
    fun byCategory(txns: List<Txn>, shares: Map<Long, List<Share>>, year: Int, month0: Int): List<Pair<String, Double>> =
        txns.filter { inMonth(it, year, month0) }
            .map { it.category.ifBlank { "Untagged" } to expenseOf(it, shares[it.id] ?: emptyList()) }
            .filter { it.second > 0.004 }
            .groupBy({ it.first }, { it.second })
            .map { (k, v) -> k to v.sum() }
            .sortedByDescending { it.second }

    /** Spent per tag over a whole calendar year, biggest first. */
    fun byCategoryYear(txns: List<Txn>, shares: Map<Long, List<Share>>, year: Int): List<Pair<String, Double>> =
        txns.filter { monthOf(it.txnDate).first == year }
            .map { it.category.ifBlank { "Untagged" } to expenseOf(it, shares[it.id] ?: emptyList()) }
            .filter { it.second > 0.004 }
            .groupBy({ it.first }, { it.second })
            .map { (k, v) -> k to v.sum() }
            .sortedByDescending { it.second }

    data class MonthRow(val label: String, val spent: Double, val income: Double)

    /** Twelve rows, January to December, for the year view. */
    fun yearMonths(txns: List<Txn>, shares: Map<Long, List<Share>>, year: Int): List<MonthRow> =
        (0..11).map { MonthRow(monthLabel(it), spentInMonth(txns, shares, year, it), incomeInMonth(txns, year, it)) }

    data class Change(val tag: String, val now: Double, val before: Double) { val delta get() = now - before }

    /** Tags whose spend moved most compared with the month before, biggest move first. Moves under ₹1 are ignored. */
    fun changes(now: List<Pair<String, Double>>, before: List<Pair<String, Double>>, max: Int = 3): List<Change> {
        val a = now.toMap(); val b = before.toMap()
        return (a.keys + b.keys).map { Change(it, a[it] ?: 0.0, b[it] ?: 0.0) }
            .filter { Math.abs(it.delta) >= 1.0 }
            .sortedByDescending { Math.abs(it.delta) }.take(max)
    }

    /** The last [n] months ending at (year, month0): label and spent, oldest first. */
    fun trend(txns: List<Txn>, shares: Map<Long, List<Share>>, year: Int, month0: Int, n: Int = 6): List<Pair<String, Double>> =
        (n - 1 downTo 0).map { back ->
            val (y, m) = shift(year, month0, -back)
            monthLabel(m) to spentInMonth(txns, shares, y, m)
        }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    fun monthLabel(month0: Int) = MONTHS[Math.floorMod(month0, 12)]

    data class Slice(val label: String, val value: Double, val fraction: Float)

    /** Pie slices: the biggest [max]-1 tags, and the rest together as "Others". */
    fun slices(entries: List<Pair<String, Double>>, max: Int = 6): List<Slice> {
        val total = entries.sumOf { it.second }
        if (total <= 0.0) return emptyList()
        val head = if (entries.size > max) entries.take(max - 1) else entries
        val rest = if (entries.size > max) entries.drop(max - 1).sumOf { it.second } else 0.0
        val all = head + (if (rest > 0.0) listOf("Others" to rest) else emptyList())
        return all.map { Slice(it.first, it.second, (it.second / total).toFloat()) }
    }

    /** Month grid for a calendar: weeks of day numbers, null for blanks. The week starts on Monday. */
    fun weeks(year: Int, month0: Int): List<List<Int?>> {
        val cal = Calendar.getInstance().apply { clear(); set(year, month0, 1) }
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7      // Monday = 0
        val cells = List(lead) { null } + (1..days).map { it as Int? }
        return cells.chunked(7).map { w -> w + List(7 - w.size) { null } }
    }
}

/** Budgets: one overall monthly limit and optional limits per tag, kept as one small text in the settings. */
object Budgets {
    const val TOTAL = "Total"

    fun decode(s: String): Map<String, Double> =
        s.split("|").mapNotNull {
            val k = it.substringBeforeLast("=", "").trim(); val v = it.substringAfterLast("=", "").trim().toDoubleOrNull()
            if (k.isEmpty() || v == null || v <= 0.0) null else k to v
        }.toMap()

    fun encode(m: Map<String, Double>): String =
        m.filterValues { it > 0.0 }.entries.joinToString("|") { "${it.key.replace("|", "").replace("=", "")}=${String.format(Locale.US, "%.2f", it.value)}" }

    fun set(m: Map<String, Double>, key: String, limit: Double?): Map<String, Double> =
        if (limit == null || limit <= 0.0) m - key else m + (key to limit)

    enum class State { OK, CLOSE, OVER }

    fun state(spent: Double, limit: Double): State = when {
        limit <= 0.0 -> State.OK
        spent > limit + 0.004 -> State.OVER
        spent >= limit * 0.8 -> State.CLOSE
        else -> State.OK
    }

    /** How far through the month [day] is, 0..1. A budget "on pace" has spent this share of its limit. */
    fun pace(day: Int, daysInMonth: Int): Float = if (daysInMonth <= 0) 0f else (day.toFloat() / daysInMonth).coerceIn(0f, 1f)

    /** A starting budget from past months: their average, rounded up to a tidy number. Null when there is no history. */
    fun suggest(pastSpent: List<Double>): Double? {
        val h = pastSpent.filter { it > 0.0 }
        if (h.isEmpty()) return null
        val avg = h.average()
        val step = if (avg >= 5000.0) 500.0 else 100.0
        return Math.ceil(avg / step) * step
    }

    fun fraction(spent: Double, limit: Double): Float = if (limit <= 0.0) 0f else (spent / limit).toFloat().coerceIn(0f, 1f)

    /** What is safe to spend each remaining day this month (today included); never negative. */
    fun perDay(limit: Double, spent: Double, daysLeft: Int): Double =
        if (daysLeft <= 0) 0.0 else Math.max(0.0, limit - spent) / daysLeft

    fun daysLeftInMonth(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return c.getActualMaximum(Calendar.DAY_OF_MONTH) - c.get(Calendar.DAY_OF_MONTH) + 1
    }
}
