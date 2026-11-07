package com.sabharish.splittracker

import kotlin.math.abs

/** A bank account you set a balance for. Balance = what you entered + money in - money out since then. */
data class Account(val key: String, val bank: String, val last: String, val name: String, val opening: Double, val asOf: Long)

data class AccountView(val key: String, val label: String, val hasBalance: Boolean, val balance: Double, val asOf: Long, val name: String)

object Accounts {
    const val PREFIX = "acct|"

    fun keyOf(bank: String, last: String) = "$bank|$last"
    const val CASH = "CASH|"
    fun label(bank: String, last: String) =
        if (bank == "CASH") "Cash" else "${bank.lowercase().replaceFirstChar { it.uppercase() }} ••$last"

    /** Accounts to pick from when adding an entry by hand: every known account, and Cash even before it has any entry. */
    fun options(views: List<AccountView>): List<AccountView> =
        if (views.any { it.key == CASH }) views else views + AccountView(CASH, "Cash", false, 0.0, 0, "")

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

    /** [window] is 10 minutes for automatic pairing; the "own transfer" button widens it to a few days (NEFT, IMPS). */
    fun find(t: Txn, candidates: List<Txn>, window: Long = WINDOW_MS): Txn? {
        if (t.kind != "UPI_OUT" && t.kind != "UPI_IN") return null
        val opposite = if (t.kind == "UPI_OUT") "UPI_IN" else "UPI_OUT"
        return candidates
            .filter { it.id != t.id && it.kind == opposite && it.status == "INBOX" }
            .filter { abs(it.amount - t.amount) < 0.011 }
            .filter { it.bank != t.bank || it.card != t.card }
            .filter { abs(it.smsTime - t.smsTime) <= window }
            .minByOrNull { abs(it.smsTime - t.smsTime) }
    }
}

data class BalanceHit(val bank: String, val last: String, val balance: Double)

/**
 * Many bank messages end with "Avl Bal Rs 4,463.90". That is the real balance at that moment, so it replaces
 * whatever we had calculated — salary, ATM and cheque entries that carry no UPI message stay correct this way.
 */
object BalanceSms {
    private val bal = Regex("""(?:avl\.? ?bal(?:ance)?|available bal(?:ance)?|current bal(?:ance)?|clear bal(?:ance)?)\s*(?:is|:|-)?\s*(?:rs\.?|inr)?\s*:?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val acct = Regex("""(?:a/?c|acct|account)\s*(?:no\.?)?\s*(?:ending)?\s*[X*x]*\s*(\d{4,})\b""", RegexOption.IGNORE_CASE)
    private val banks = listOf("SBI" to "SBI", "ICICI" to "ICICI", "HDFC" to "HDFC", "KOTAK" to "KOTAK", "FEDERAL" to "FEDERAL",
        "DBS" to "DBS", "YES BANK" to "YES", "INDUSIND" to "INDUSIND", "UNION BANK" to "UNION", "BOI" to "BOI", "AXIS" to "AXIS")

    fun parse(body: String?, sender: String? = null): BalanceHit? {
        if (body.isNullOrBlank()) return null
        val b = body.replace(Regex("\\s+"), " ")
        if (Regex("credit card|card ending|spent on", RegexOption.IGNORE_CASE).containsMatchIn(b)) return null
        val amount = bal.find(b)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val last = acct.find(b)?.groupValues?.get(1)?.takeLast(4) ?: return null
        val bank = Banks.detect(b, sender) ?: return null
        return BalanceHit(bank, last, amount)
    }
}

/** Entries made by hand, for money that sends no SMS: cash, other banks, wallets. */
object Entries {
    const val OWN = "Transfer between your own accounts"
    const val DAYS3 = 3 * 24 * 60 * 60_000L

    fun bankOf(key: String) = key.substringBefore("|")
    fun lastOf(key: String) = key.substringAfter("|")

    fun spent(key: String, title: String, amount: Double, now: Long) = Txn(
        bank = bankOf(key), card = lastOf(key), kind = "UPI_OUT", amount = amount,
        merchant = title.trim().ifEmpty { "Added by me" }, txnDate = now, smsTime = now, status = "INBOX", note = "Added by hand"
    )

    fun received(key: String, title: String, amount: Double, now: Long) = Txn(
        bank = bankOf(key), card = lastOf(key), kind = "UPI_IN", amount = amount,
        merchant = title.trim().ifEmpty { "Added by me" }, txnDate = now, smsTime = now, status = "INBOX", note = "Added by hand"
    )

    /** Money moved between two of your accounts: one debit and one credit, both kept out of your spends. */
    fun moved(fromKey: String, toKey: String, fromLabel: String, toLabel: String, amount: Double, now: Long): Pair<Txn, Txn> =
        Txn(bank = bankOf(fromKey), card = lastOf(fromKey), kind = "UPI_OUT", amount = amount, merchant = "To $toLabel",
            txnDate = now, smsTime = now, status = "SELF", note = OWN) to
        Txn(bank = bankOf(toKey), card = lastOf(toKey), kind = "UPI_IN", amount = amount, merchant = "From $fromLabel",
            txnDate = now, smsTime = now, status = "SELF", note = OWN)
}
