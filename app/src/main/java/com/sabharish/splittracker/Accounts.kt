package com.sabharish.splittracker

import kotlin.math.abs

/** A bank account you set a balance for. Balance = what you entered + money in - money out since then. */
data class Account(val key: String, val bank: String, val last: String, val name: String, val opening: Double, val asOf: Long)

data class AccountView(val key: String, val label: String, val hasBalance: Boolean, val balance: Double, val asOf: Long, val name: String)

object Accounts {
    const val PREFIX = "acct|"

    fun keyOf(bank: String, last: String) = "$bank|$last"
    fun label(bank: String, last: String) = "${bank.lowercase().replaceFirstChar { it.uppercase() }} ••$last"

    fun isCredit(t: Txn) = t.kind == "UPI_IN" || (t.kind == "REFUND" && t.merchant == "UPI reversal")
    fun isDebit(t: Txn) = t.kind == "UPI_OUT" || (t.kind == "SPEND" && t.bank == "KOTAK")

    /** Accounts that show up in UPI messages (these carry the account's last digits). */
    fun detected(txns: List<Txn>): List<Pair<String, String>> =
        txns.filter { it.kind == "UPI_IN" || it.kind == "UPI_OUT" || (it.kind == "REFUND" && it.merchant == "UPI reversal") }
            .map { it.bank to it.card }.distinct().sortedBy { it.first + it.second }

    /** Which account a transaction moved money in or out of, if any. Debit card spends belong to the bank's only account. */
    fun accountOf(t: Txn, detected: List<Pair<String, String>>): String? = when {
        t.kind == "SPEND" && t.bank == "KOTAK" ->
            detected.filter { it.first == "KOTAK" }.let { if (it.size == 1) keyOf(it[0].first, it[0].second) else null }
        isCredit(t) || t.kind == "UPI_OUT" -> keyOf(t.bank, t.card)
        else -> null
    }

    fun balance(a: Account, txns: List<Txn>, detected: List<Pair<String, String>>): Double {
        var b = a.opening
        for (t in txns) {
            if (t.smsTime < a.asOf || accountOf(t, detected) != a.key) continue
            if (isCredit(t)) b += t.amount else if (isDebit(t)) b -= t.amount
        }
        return Math.round(b * 100) / 100.0
    }

    // stored in the rule table as  acct|BANK|1234 -> "name;opening;asOf"
    fun encode(a: Account) = Rule(PREFIX + a.key, "ACCT", "${a.name.replace(";", " ")};${a.opening};${a.asOf}")

    fun decode(r: Rule): Account? {
        if (!r.ruleKey.startsWith(PREFIX)) return null
        val key = r.ruleKey.removePrefix(PREFIX)
        val parts = r.people.split(";")
        if (parts.size < 3) return null
        val bank = key.substringBefore("|"); val last = key.substringAfter("|")
        return Account(key, bank, last, parts[0], parts[1].toDoubleOrNull() ?: return null, parts[2].toLongOrNull() ?: return null)
    }

    fun views(txns: List<Txn>, rules: List<Rule>): List<AccountView> {
        val det = detected(txns)
        val saved = rules.mapNotNull { decode(it) }.associateBy { it.key }
        val keys = (det.map { keyOf(it.first, it.second) } + saved.keys).distinct().sorted()
        return keys.map { k ->
            val a = saved[k]
            val bank = k.substringBefore("|"); val last = k.substringAfter("|")
            val lbl = if (a != null && a.name.isNotBlank()) a.name else label(bank, last)
            if (a == null) AccountView(k, lbl, false, 0.0, 0, "")
            else AccountView(k, lbl, true, balance(a, txns, det), a.asOf, a.name)
        }
    }
}

/** Spots a transfer between two of your own accounts: money out of one, same amount in to another, minutes apart. */
object TransferMatcher {
    const val WINDOW_MS = 10 * 60_000L

    fun find(t: Txn, candidates: List<Txn>): Txn? {
        if (t.kind != "UPI_OUT" && t.kind != "UPI_IN") return null
        val opposite = if (t.kind == "UPI_OUT") "UPI_IN" else "UPI_OUT"
        return candidates
            .filter { it.id != t.id && it.kind == opposite && it.status == "INBOX" }
            .filter { abs(it.amount - t.amount) < 0.011 }
            .filter { it.bank != t.bank || it.card != t.card }
            .filter { abs(it.smsTime - t.smsTime) <= WINDOW_MS }
            .minByOrNull { abs(it.smsTime - t.smsTime) }
    }
}
