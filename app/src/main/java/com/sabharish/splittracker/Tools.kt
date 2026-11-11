package com.sabharish.splittracker

import java.util.Calendar

/** Tiny calculator for the amount field: "120+45*2" → 210. Digits, + − × ÷ only; a dangling operator is ignored. */
object Calc {
    fun eval(raw: String): Double? {
        val s = raw.replace(",", "").replace("×", "*").replace("÷", "/").replace("−", "-").replace(" ", "").trimEnd('+', '-', '*', '/')
        if (s.isEmpty()) return null
        // split into numbers and operators; a leading '-' is not supported (amounts are positive)
        val nums = mutableListOf<Double>(); val ops = mutableListOf<Char>()
        var cur = StringBuilder()
        for (c in s) {
            if (c in "+-*/") {
                if (cur.isEmpty()) return null
                nums += cur.toString().toDoubleOrNull() ?: return null; cur = StringBuilder(); ops += c
            } else if (c.isDigit() || c == '.') cur.append(c) else return null
        }
        nums += cur.toString().toDoubleOrNull() ?: return null
        // × and ÷ first
        var i = 0
        while (i < ops.size) {
            if (ops[i] == '*' || ops[i] == '/') {
                val v = if (ops[i] == '*') nums[i] * nums[i + 1] else if (nums[i + 1] == 0.0) return null else nums[i] / nums[i + 1]
                nums[i] = v; nums.removeAt(i + 1); ops.removeAt(i)
            } else i++
        }
        var total = nums[0]
        ops.forEachIndexed { k, o -> total = if (o == '+') total + nums[k + 1] else total - nums[k + 1] }
        return if (total.isFinite()) Math.round(total * 100) / 100.0 else null
    }

    fun hasOperator(raw: String) = raw.trimEnd('+', '-', '×', '÷', '−', '*', '/').any { it in "+-×÷−*/" }
}

/** A spreadsheet-friendly copy of your transactions. Built on the phone and saved only where you choose. */
object Csv {
    private fun cell(v: String): String {
        // a cell starting with = + - @ would run as a formula in a spreadsheet
        val safe = if (v.isNotEmpty() && v[0] in "=+-@") "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun build(txns: List<Txn>, shares: Map<Long, List<Share>>): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH)
        val sb = StringBuilder("Date,Type,Amount,Merchant,Category,Account,Status,My share,Note\n")
        txns.sortedBy { it.txnDate }.forEach { t ->
            val type = when {
                Stats.incomeOf(t) > 0 -> "Income"
                t.status == "SELF" -> "Transfer"
                t.kind == "UPI_IN" || t.kind == "REFUND" -> "Money in"
                else -> "Expense"
            }
            val mine = if (t.status == "TAGGED") (shares[t.id] ?: emptyList()).filter { it.person == Logic.ME }.sumOf { it.amount } else 0.0
            sb.append(listOf(fmt.format(t.txnDate), type, "%.2f".format(java.util.Locale.ENGLISH, t.amount), cell(t.merchant), cell(t.category),
                cell(listOf(t.bank, t.card).filter { it.isNotBlank() }.joinToString(" ")), t.status,
                if (t.status == "TAGGED") "%.2f".format(java.util.Locale.ENGLISH, mine) else "", cell(t.note)).joinToString(",")).append('\n')
        }
        return sb.toString()
    }
}

/** An entry that repeats every month (rent, a subscription, a salary). Kept in a setting, so it is backed up too. */
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

/** When to ask for the phone's screen lock again: after the app has been away for a short while. */
object LockRule {
    const val GRACE = 30_000L
    fun shouldLock(lockOn: Boolean, stoppedAt: Long, now: Long) = lockOn && stoppedAt > 0 && now - stoppedAt > GRACE
}

/** A `upi://pay` link: opens the payer's own UPI app with the amount filled in. Built here, never sent anywhere by this app. */
object UpiLink {
    /** Percent-encoding by hand (letters, digits and - _ . ~ @ stay as they are). */
    private fun enc(s: String) = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~@") append(c) else append("%%%02X".format(b.toInt() and 0xFF))
        }
    }
    fun build(upi: String, amount: Double, note: String): String =
        "upi://pay?pa=${enc(upi.trim())}&am=${"%.2f".format(java.util.Locale.ENGLISH, amount)}&cu=INR&tn=${enc(note.trim().ifEmpty { "Payment" })}"
}

/** Quiet status lines for Home: is capture working, and is there a recent backup? */
object Health {
    data class Line(val text: String, val warn: Boolean)
    private const val DAY = 86_400_000L

    fun ago(ms: Long): String = when {
        ms < 60_000L -> "just now"
        ms < 3_600_000L -> "${ms / 60_000L} min ago"
        ms < DAY -> "${ms / 3_600_000L} h ago"
        ms < 2 * DAY -> "yesterday"
        else -> "${ms / DAY} days ago"
    }

    /** When the newest bank message or notification was read. Hand-made entries have no message text and are ignored. */
    fun capture(txns: List<Txn>, now: Long): Line? {
        val last = txns.filter { it.body.isNotBlank() }.maxOfOrNull { it.smsTime } ?: return null
        val age = (now - last).coerceAtLeast(0)
        return Line("Last bank message read ${ago(age)}" + if (age > 3 * DAY) ". If you have paid since, check Settings ▸ Message reading check." else "", age > 3 * DAY)
    }

    fun backup(lastBackup: Long, autoOn: Boolean, txnCount: Int, now: Long): Line? {
        if (txnCount < 20) return null
        val age = now - lastBackup
        return when {
            lastBackup <= 0 -> Line("No backup yet. Turn on automatic backup in More.", true)
            age > 14 * DAY || (autoOn && age > 3 * DAY) -> Line("Last backup ${ago(age)}." + if (autoOn) " Open the app to run the automatic one." else " Turn on automatic backup in More.", true)
            else -> null
        }
    }
}
