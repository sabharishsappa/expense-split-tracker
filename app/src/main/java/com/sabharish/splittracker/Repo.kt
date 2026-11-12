package com.sabharish.splittracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NewShare(val person: String, val amount: Double, val paid: Boolean)

object Repo {
    const val ME = Logic.ME

    // ---------- formatting ----------
    fun money(v: Double): String = Logic.money(v)
    fun compact(v: Double): String = Logic.compact(v)

    fun shortDate(ms: Long): String = Logic.shortDate(ms)

    private val bankNames = mapOf(
        "SBI" to "SBI", "HDFC" to "HDFC", "ICICI" to "ICICI", "INDUSIND" to "IndusInd",
        "YES" to "YES Bank", "KOTAK" to "Kotak", "FEDERAL" to "Federal"
    )

    fun bankName(bank: String): String = bankNames[bank] ?: bank

    /** "SBI ••6674" for cards/accounts with digits, plain bank name otherwise. */
    fun acct(bank: String, card: String): String {
        val b = bankNames[bank] ?: bank
        if (bank == "UPI") return card   // seen in a UPI app: Google Pay, PhonePe…
        return if (card.isNotEmpty() && card.all { it.isDigit() }) "$b ••$card" else b
    }

    // ---------- keys ----------
    private fun norm(s: String) = Logic.norm(s)
    /** Remembered choices are per merchant (any card or account). */
    fun merchantKey(merchant: String, @Suppress("UNUSED_PARAMETER") card: String) = "m|" + norm(merchant)
    fun catKey(merchant: String) = "cat|" + norm(merchant)
    fun aliasKey(raw: String) = "alias|" + norm(raw)

    fun title(raw: String, aliases: Map<String, String>): String = Logic.title(raw, aliases)
    fun nameOnly(raw: String, aliases: Map<String, String>): String = Logic.nameOnly(raw, aliases)

    // ---------- preferences ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("st", Context.MODE_PRIVATE)
    fun getPref(ctx: Context, key: String, def: String) = prefs(ctx).getString(key, def) ?: def
    fun putPref(ctx: Context, key: String, v: String) = prefs(ctx).edit().putString(key, v).apply()

    private fun selfNames(ctx: Context): List<String> =
        getPref(ctx, "selfnames", "").split(",").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }

    private fun isSelf(ctx: Context, name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return selfNames(ctx).any { n.contains(it) }
    }

    /** The same SMS seen twice (live + inbox read) differs by seconds; two real purchases are further apart than this. */
    private const val DEDUPE_MS = 45_000L

    // ---------- ingest ----------
    /** Stores one parsed SMS. Returns the new transaction when it needs the user's attention. */
    suspend fun ingest(ctx: Context, p: Parsed, smsTime: Long, body: String = ""): Txn? {
        val db = Db.get(ctx)
        val dao = db.dao()
        return db.withTransaction {
            if (dao.ruleCount(Logic.deletedKey(p.kind, p.amount, smsTime)) > 0) return@withTransaction null   // you deleted this one
            val dup = dao.near(p.kind, p.amount, p.card, p.merchant, smsTime - DEDUPE_MS, smsTime + DEDUPE_MS)
            if (dup > 0) {
                if (body.isNotBlank()) dao.fillBody(p.kind, p.amount, p.card, p.merchant, smsTime - DEDUPE_MS, smsTime + DEDUPE_MS, body)
                return@withTransaction null
            }
            // the same payment may also arrive by notification, bank app or email: keep one entry
            val cands = dao.sameAmount(p.kind, p.amount - 0.011, p.amount + 0.011, smsTime - Dedupe.BODY_WINDOW_MS, smsTime + Dedupe.BODY_WINDOW_MS)
            if (Dedupe.isDuplicate(cands, p, body, smsTime)) return@withTransaction null
            val near = cands.filter { Math.abs(it.smsTime - smsTime) <= CrossSource.WINDOW_MS }
            when (val d = CrossSource.decide(near, p, body, smsTime)) {
                is CrossSource.Decision.Drop -> return@withTransaction null
                is CrossSource.Decision.Upgrade -> { dao.upgradeFromSms(d.twin.id, p.bank, p.card, d.merchant, body); return@withTransaction null }
                else -> {}
            }
            val isUpi = p.kind.startsWith("UPI")
            var status = when (p.kind) {
                "SPEND", "UPI_OUT", "UPI_IN", "REFUND" -> "INBOX"
                else -> "BILLPAY"
            }
            if (isUpi && isSelf(ctx, p.merchant)) status = "SELF"
            else if (p.kind == "UPI_IN" && p.amount < 10) status = "IGNORED"

            var transferNote = ""
            var pairedWith: Txn? = null
            if (status == "INBOX" && p.kind.startsWith("UPI")) {
                val probe = Txn(bank = p.bank, card = p.card, kind = p.kind, amount = p.amount, merchant = p.merchant,
                    txnDate = p.dateMillis ?: smsTime, smsTime = smsTime, status = "INBOX")
                val other = if (p.kind == "UPI_OUT") "UPI_IN" else "UPI_OUT"
                pairedWith = TransferMatcher.find(probe, dao.transferCandidates(other, p.amount - 0.011, p.amount + 0.011,
                    smsTime - TransferMatcher.WINDOW_MS, smsTime + TransferMatcher.WINDOW_MS))
                if (pairedWith != null) { status = "SELF"; transferNote = "Transfer between your own accounts" }
            }
            var cat = ""
            if (p.kind == "SPEND" || p.kind == "UPI_OUT") {
                cat = dao.rule(catKey(p.merchant))?.people ?: Categories.guess(p.merchant)
            }
            val t = Txn(
                bank = p.bank, card = p.card, kind = p.kind, amount = p.amount, merchant = p.merchant,
                txnDate = p.dateMillis ?: smsTime, smsTime = smsTime, status = status, category = cat, body = body
            )
            val id = dao.insert(t.copy(note = transferNote))
            var saved = t.copy(id = id, note = transferNote)
            pairedWith?.let { dao.setStatusNote(it.id, "SELF", "Transfer between your own accounts") }

            if ((p.kind == "SPEND" || p.kind == "UPI_OUT") && status == "INBOX") {
                val rule = dao.rule(merchantKey(p.merchant, p.card))
                // "mine" is assumed only when the history is clear and the amount is usual; otherwise you are asked
                val mine = (rule != null && rule.action == "MINE" &&
                    AutoTag.allowed(dao.pastTags(p.merchant, p.card).map { AutoTag.Past(it.amount, it.others) }, p.amount)) ||
                    (rule == null && Categories.defaultMine(p.merchant))
                if (mine) {
                    dao.insertShare(Share(txnId = id, person = ME, amount = p.amount, settled = p.amount))
                    dao.setStatus(id, "TAGGED")
                    dao.setAuto(id, true)
                    saved = saved.copy(status = "TAGGED", auto = true)
                }
            }
            if (p.kind == "PAYMENT") {
                Bills.billPaidBy(dao.unpaidBills(), p.bank, p.card, p.amount, smsTime)?.let { dao.setBillPaid(it.id, true) }
            }
            if (p.kind == "REFUND") {
                val found = findOriginal(dao, saved)
                if (found != null && found.second) {
                    applyRefund(dao, saved, found.first)
                    saved = saved.copy(status = "REFUND")
                }
            }
            if (p.kind == "UPI_IN" && saved.status == "INBOX") {
                val rows = dao.pendingNow()
                val ids = Logic.autoSettle(rows, p.merchant, p.amount)
                val who = ids?.let { rows.firstOrNull { r -> r.shareId == it.first() }?.person }
                if (ids != null && who != null) {
                    val applied = ArrayList<String>()
                    for (sid in ids) {
                        val sh = dao.share(sid) ?: continue
                        applied.add("$sid:${sh.amount - sh.settled}")
                        dao.setSettled(sid, sh.amount)
                    }
                    val settleId = dao.insertSettlement(Settlement(person = who, amount = p.amount, date = System.currentTimeMillis(), mode = "UPI"))
                    dao.putRule(Rule("auto|$id", "AUTO", "$settleId;" + applied.joinToString(",")))
                    dao.setStatusNote(id, "REPAID", "Paid by $who · matched automatically")
                    saved = saved.copy(status = "REPAID", note = "Paid by $who · matched automatically")
                }
            }
            if (saved.status == "INBOX" || saved.status == "REPAID") saved else null
        }
    }

    // ---------- refunds and failed payments ----------
    /** Purchases that could be the one this refund belongs to (amount equal, shortly before it). */
    private suspend fun rawCandidates(dao: AppDao, refund: Txn): List<Txn> {
        val days = if (RefundMatcher.isUpiReversal(refund)) 15L else 120L
        val from = refund.smsTime - days * 86_400_000L
        return dao.refundCandidates(refund.amount - 0.011, refund.amount + 0.011, from, refund.smsTime)
    }

    private suspend fun findOriginal(dao: AppDao, refund: Txn): Pair<Txn, Boolean>? =
        RefundMatcher.find(refund, rawCandidates(dao, refund))

    suspend fun suggestOriginal(ctx: Context, refund: Txn): Txn? = findOriginal(Db.get(ctx).dao(), refund)?.first

    private suspend fun applyRefund(dao: AppDao, refund: Txn, orig: Txn) {
        val ratio = minOf(1.0, refund.amount / orig.amount)
        for (s in dao.sharesFor(orig.id)) {
            dao.setShareAmount(s.id, Math.round(s.amount * (1 - ratio) * 100) / 100.0)
        }
        val full = ratio > 0.995
        if (full) dao.setStatus(orig.id, "REVERSED")
        dao.setRefund(refund.id, orig.id, "REFUND", (if (full) "Reversal of " else "Partial refund for ") + orig.merchant)
    }

    /** User says this payment failed. Closes automatically when the matching refund arrives (or already did). */
    suspend fun markFailed(ctx: Context, t: Txn): Boolean {
        val db = Db.get(ctx); val dao = db.dao()
        return db.withTransaction {
            dao.deleteShares(t.id)
            dao.setStatus(t.id, "FAILED")
            dao.setAuto(t.id, false)
            val waiting = dao.refundsWaiting(t.amount - 0.011, t.amount + 0.011, t.smsTime)
            if (waiting.size == 1) { applyRefund(dao, waiting[0], t); true } else false
        }
    }

    /** Refund received that the app did not see: the money is back, so the spend (or part of it) no longer counts. */
    suspend fun markRefunded(ctx: Context, t: Txn, amount: Double) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            val ratio = minOf(1.0, amount / t.amount)
            if (ratio <= 0.0) return@withTransaction
            for (s in dao.sharesFor(t.id)) dao.setShareAmount(s.id, Math.round(s.amount * (1 - ratio) * 100) / 100.0)
            val full = ratio > 0.995
            dao.setRefund(t.id, 0, if (full) "REVERSED" else t.status,
                if (full) "Refund received (marked by you)" else "Partial refund ${money(amount)} received (marked by you)")
        }
    }

    suspend fun unfail(ctx: Context, id: Long) = Db.get(ctx).dao().setStatus(id, "INBOX")

    suspend fun linkRefund(ctx: Context, refundId: Long, originalId: Long) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            val r = dao.get(refundId) ?: return@withTransaction
            val o = dao.get(originalId) ?: return@withTransaction
            applyRefund(dao, r, o)
        }
    }

    suspend fun keepAsRefund(ctx: Context, id: Long) = Db.get(ctx).dao().setStatus(id, "REFUND")

    suspend fun recentSpends(ctx: Context, refundAmount: Double): List<Txn> {
        val list = Db.get(ctx).dao().recentSpends()
        return list.sortedBy { Math.abs(it.amount - refundAmount) }.take(30)
    }

    // ---------- tagging ----------
    fun equalShares(total: Double, names: List<String>, paidOthers: Boolean) = Logic.equalShares(total, names, paidOthers)

    fun weightedShares(total: Double, names: List<String>, weights: List<Double>, paidOthers: Boolean) =
        Logic.weightedShares(total, names, weights, paidOthers)

    suspend fun setAlias(ctx: Context, raw: String, name: String) {
        val dao = Db.get(ctx).dao()
        val n = name.trim()
        if (n.isEmpty()) dao.deleteRule(aliasKey(raw)) else dao.putRule(Rule(aliasKey(raw), "ALIAS", n))
        for (t in dao.uncategorised()) {
            if (t.merchant == raw) {
                val c = Categories.guess(n.ifEmpty { raw })
                if (c.isNotBlank()) dao.setCategory(t.id, c)
            }
        }
    }

    suspend fun tag(ctx: Context, t: Txn, shares: List<NewShare>, category: String, remember: Boolean, splitAdded: Boolean) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            val settledNow = Logic.carrySettled(dao.sharesFor(t.id), shares)
            dao.deleteShares(t.id)
            shares.forEachIndexed { i, s ->
                dao.insertShare(Share(txnId = t.id, person = s.person, amount = s.amount, settled = settledNow[i]))
            }
            dao.setStatus(t.id, "TAGGED")
            dao.setAuto(t.id, false)
            dao.setCategory(t.id, category)
            if (splitAdded) dao.setSplitAdded(t.id, true)
            if (remember) {
                val onlyMe = shares.size == 1 && shares[0].person == ME
                dao.putRule(Rule(merchantKey(t.merchant, t.card), if (onlyMe) "MINE" else "PEOPLE", shares.joinToString(",") { it.person }))
                if (category.isNotBlank()) dao.putRule(Rule(catKey(t.merchant), "CAT", category))
            }
        }
    }

    suspend fun tagAllMine(ctx: Context, list: List<Txn>) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            for (t in list) {
                dao.insertShare(Share(txnId = t.id, person = ME, amount = t.amount, settled = t.amount))
                dao.setStatus(t.id, "TAGGED")
            }
        }
    }

    /** Ticking a draft: an untagged spend becomes yours (with its usual tag); an auto-sorted one just loses its draft mark. */
    suspend fun confirmDrafts(ctx: Context, list: List<Txn>) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            for (t in list) {
                if (t.status == "INBOX" && t.kind != "SPEND" && t.kind != "UPI_OUT") {
                    val back = t.kind == "REFUND" || t.merchant.contains("reversal", true) || t.merchant.contains("refund", true)
                    dao.setStatusNote(t.id, "RECEIVED", if (back) "Refund" else "Received: Income")
                } else if (t.status == "INBOX") {
                    if (dao.sharesFor(t.id).isEmpty()) dao.insertShare(Share(txnId = t.id, person = ME, amount = t.amount, settled = t.amount))
                    dao.setStatus(t.id, "TAGGED")
                    dao.setCategory(t.id, t.category.ifBlank { Categories.guess(t.merchant) })
                }
                dao.setAuto(t.id, false)
            }
        }
    }

    suspend fun untag(ctx: Context, id: Long) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction { dao.deleteShares(id); dao.setStatus(id, "INBOX") }
    }

    /** Removes the transaction and its split. A small note keeps the same message from coming back on a re-read. */
    suspend fun deleteTxn(ctx: Context, t: Txn) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            dao.deleteShares(t.id); dao.unlinkRefunds(t.id); dao.deleteTxnRow(t.id)
            dao.putRule(Rule(Logic.deletedKey(t.kind, t.amount, t.smsTime), "DEL", ""))
        }
    }

    /** Old tag names become their new names in saved transactions, rules and tag lists. Safe to repeat. */
    suspend fun migrateNames(ctx: Context) {
        val dao = Db.get(ctx).dao()
        Categories.renamed.forEach { (old, new) ->
            dao.renameCategory(old, new); dao.renameCatRules(old, new)
            listOf("tags", "hiddenTags").forEach { k ->
                val cur = Logic.parseTags(getPref(ctx, k, ""))
                if (old in cur) putPref(ctx, k, Logic.joinTags(cur.map { if (it == old) new else it }.distinct()))
            }
        }
    }

    suspend fun ignore(ctx: Context, t: Txn) = Db.get(ctx).dao().setStatus(t.id, "IGNORED")
    /** Tags this transaction and every untagged one from the same merchant. Returns how many others were tagged. */
    suspend fun setCategory(ctx: Context, t: Txn, c: String): Int {
        val dao = Db.get(ctx).dao()
        dao.setCategory(t.id, c)
        if (c.isBlank()) return 0
        dao.putRule(Rule(catKey(t.merchant), "CAT", c))
        val same = Logic.similar(t, dao.uncategorised())
        same.forEach { dao.setCategory(it.id, c) }
        return same.size
    }

    /** Gives every selected spend the same tag, remembering it per merchant. */
    suspend fun bulkCategory(ctx: Context, txns: List<Txn>, c: String) {
        val dao = Db.get(ctx).dao()
        for (t in txns) {
            dao.setCategory(t.id, c)
            if (c.isNotBlank()) dao.putRule(Rule(catKey(t.merchant), "CAT", c))
        }
    }

    /** One go: optional tag and optional people for every ticked bill. Returns how many spends got people. */
    suspend fun bulkApply(ctx: Context, txns: List<Txn>, tag: String, people: List<String>): Int {
        if (tag.isNotBlank()) bulkCategory(ctx, txns, tag)
        return if (people.isNotEmpty()) bulkPeople(ctx, txns.map { if (tag.isNotBlank()) it.copy(category = tag) else it }, people) else 0
    }

    /** Splits every selected spend equally between [people] (one person = all theirs). Skips money-in rows. */
    suspend fun bulkPeople(ctx: Context, txns: List<Txn>, people: List<String>): Int {
        if (people.isEmpty()) return 0
        val spends = txns.filter { it.kind == "SPEND" || it.kind == "UPI_OUT" }
        for (t in spends) {
            val shares = if (people.size == 1) listOf(NewShare(people[0], t.amount, people[0] == ME)) else equalShares(t.amount, people, false)
            tag(ctx, t, shares, t.category, false, false)
        }
        return spends.size
    }

    suspend fun renameTag(ctx: Context, old: String, new: String) {
        val dao = Db.get(ctx).dao(); dao.renameCategory(old, new); dao.renameCatRules(old, new)
    }

    /** Deleting a tag leaves its transactions untagged and forgets the merchants mapped to it. */
    suspend fun deleteTag(ctx: Context, name: String) {
        val dao = Db.get(ctx).dao(); dao.renameCategory(name, ""); dao.deleteCatRules(name)
    }

    /** Phone number for a contact name: one you typed before, else the one in your contacts. */
    suspend fun phoneFor(ctx: Context, name: String): String = withContext(Dispatchers.IO) {
        val saved = getPref(ctx, "phone|" + Logic.norm(name), "")
        if (saved.isNotEmpty()) return@withContext saved
        if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) return@withContext ""
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", arrayOf(name), null
        ) ?: return@withContext ""
        c.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
    }

    fun savePhone(ctx: Context, name: String, number: String) = putPref(ctx, "phone|" + Logic.norm(name), number)

    suspend fun removeTagRule(ctx: Context, key: String) = Db.get(ctx).dao().deleteRule(key)

    suspend fun suggestion(ctx: Context, t: Txn): Rule? = Db.get(ctx).dao().rule(merchantKey(t.merchant, t.card))

    suspend fun backfillCategories(ctx: Context) {
        val dao = Db.get(ctx).dao()
        for (t in dao.uncategorised()) {
            val c = dao.rule(catKey(t.merchant))?.people ?: Categories.guess(t.merchant)
            if (c.isNotBlank()) dao.setCategory(t.id, c)
        }
    }

    // ---------- settlements ----------
    private suspend fun applySettlement(dao: AppDao, person: String, amount: Double, mode: String) {
        var left = amount
        for (s in dao.openShares(person)) {
            if (left <= 0.004) break
            val pay = minOf(s.amount - s.settled, left)
            dao.setSettled(s.id, s.settled + pay)
            left -= pay
        }
        dao.insertSettlement(Settlement(person = person, amount = amount, date = System.currentTimeMillis(), mode = mode))
    }

    suspend fun settle(ctx: Context, person: String, amount: Double, mode: String) {
        val db = Db.get(ctx)
        db.withTransaction { applySettlement(db.dao(), person, amount, mode) }
    }

    /** Marks exactly the ticked bills as fully paid. */
    suspend fun settleShares(ctx: Context, person: String, shareIds: Set<Long>, mode: String) {
        val db = Db.get(ctx)
        db.withTransaction {
            val dao = db.dao()
            var total = 0.0
            for (id in shareIds) {
                val s = dao.share(id) ?: continue
                if (s.person != person) continue
                val due = s.amount - s.settled
                if (due <= 0.004) continue
                dao.setSettled(s.id, s.amount)
                total += due
            }
            if (total > 0.004) dao.insertSettlement(Settlement(person = person, amount = Math.round(total * 100) / 100.0, date = System.currentTimeMillis(), mode = mode))
        }
    }

    /** A received UPI payment is a repayment from [person]. */
    suspend fun repaidBy(ctx: Context, t: Txn, person: String) {
        val db = Db.get(ctx)
        db.withTransaction {
            applySettlement(db.dao(), person, t.amount, "UPI")
            db.dao().setStatusNote(t.id, "REPAID", "Repayment from $person")
        }
    }

    fun bestMatch(name: String, people: List<String>): String? = Logic.bestMatch(name, people)

    // ---------- statements ----------
    /** Adds a statement line the app never saw, as an untagged spend. */
    suspend fun addFromStatement(ctx: Context, s: StmtSource, l: StmtLine) {
        val kind = if (s.isCard) "SPEND" else "UPI_OUT"
        Db.get(ctx).dao().insert(Txn(bank = s.bank, card = s.last, kind = kind, amount = l.amount, merchant = Logic.title(l.desc.take(40), emptyMap()),
            txnDate = l.date, smsTime = l.date, status = "INBOX", note = "Added from statement"))
    }

    /** Takes back an automatic match: the bills become unpaid again and the payment returns to the Inbox. */
    suspend fun undoAutoSettle(ctx: Context, t: Txn) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            val r = dao.rule("auto|${t.id}")
            if (r != null) {
                val parts = r.people.split(";")
                parts.getOrNull(0)?.toLongOrNull()?.let { dao.deleteSettlement(it) }
                parts.getOrNull(1)?.split(",")?.filter { it.isNotBlank() }?.forEach { pair ->
                    val sid = pair.substringBefore(":").toLongOrNull(); val amt = pair.substringAfter(":").toDoubleOrNull()
                    val sh = if (sid != null) dao.share(sid) else null
                    if (sh != null && amt != null) dao.setSettled(sh.id, Math.max(0.0, sh.settled - amt))
                }
                dao.deleteRule("auto|${t.id}")
            }
            dao.setStatusNote(t.id, "INBOX", "")
        }
    }

    // ---------- messages the parser did not understand (kept on this phone, for diagnosis) ----------
    fun noteUnparsed(ctx: Context, body: String) {
        val key = body.replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim().take(60)
        val old = getPref(ctx, "unparsed", "").split("\u0001").filter { it.isNotBlank() }
        if (old.any { it.replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim().take(60) == key }) return
        putPref(ctx, "unparsed", (listOf(body.replace("\u0001", " ").take(300)) + old).take(15).joinToString("\u0001"))
    }
    fun unparsed(ctx: Context): List<String> = getPref(ctx, "unparsed", "").split("\u0001").filter { it.isNotBlank() }
    fun clearUnparsed(ctx: Context) = putPref(ctx, "unparsed", "")

    // ---------- money you owe ----------
    /** The money received in [t] was borrowed: you will need to pay [person] back. */
    suspend fun borrowed(ctx: Context, t: Txn, person: String) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            dao.insertPayable(Payable(person = person, amount = t.amount, date = t.txnDate, note = "Borrowed", txnId = t.id))
            dao.setStatusNote(t.id, "RECEIVED", "Borrowed from $person · you owe this back")
        }
    }
    /** Money received for some other reason (salary, gift, sale…): out of the Inbox, reason kept as a note. */
    suspend fun receivedFor(ctx: Context, t: Txn, reason: String) = Db.get(ctx).dao().setStatusNote(t.id, "RECEIVED", reason)
    suspend fun addPayable(ctx: Context, person: String, amount: Double, note: String) {
        Db.get(ctx).dao().insertPayable(Payable(person = person, amount = amount, date = System.currentTimeMillis(), note = note))
    }
    /** You paid [amount] towards this debt (capped at what is left). */
    suspend fun payPayable(ctx: Context, id: Long, amount: Double) {
        val dao = Db.get(ctx).dao(); val p = dao.payable(id) ?: return
        dao.setPayablePaid(id, Logic.paidAfter(p.amount, p.paid, amount))
    }

    // ---------- entries made by hand ----------
    /** A spend that has no SMS (cash, another app). Starts untagged so the normal split page can finish it. */
    suspend fun addManual(ctx: Context, title: String, amount: Double, accountKey: String = Accounts.CASH): Txn {
        val dao = Db.get(ctx).dao()
        val t = Entries.spent(accountKey, title, amount, System.currentTimeMillis())
        return t.copy(id = dao.insert(t))
    }

    suspend fun addReceived(ctx: Context, title: String, amount: Double, accountKey: String) {
        Db.get(ctx).dao().insert(Entries.received(accountKey, title, amount, System.currentTimeMillis()))
    }

    suspend fun addMoved(ctx: Context, fromKey: String, toKey: String, fromLabel: String, toLabel: String, amount: Double, at: Long = System.currentTimeMillis(), note: String = "") {
        val db = Db.get(ctx); val dao = db.dao()
        val (out, inn) = Entries.moved(fromKey, toKey, fromLabel, toLabel, amount, at)
        val n = note.trim()
        db.withTransaction { dao.insert(if (n.isEmpty()) out else out.copy(note = n)); dao.insert(if (n.isEmpty()) inn else inn.copy(note = n)) }
    }

    /** An expense typed in with its category and day: it is yours, so it is tagged straight away and never waits in the Inbox. */
    suspend fun addHandExpense(ctx: Context, title: String, amount: Double, accountKey: String, category: String, note: String, at: Long) {
        val db = Db.get(ctx); val dao = db.dao()
        val base = Entries.spent(accountKey, title.ifBlank { category.ifBlank { "Added by me" } }, amount, at)
        db.withTransaction {
            val id = dao.insert(base.copy(status = "TAGGED", category = category, note = note.trim().ifEmpty { "Added by hand" }))
            dao.insertShare(Share(txnId = id, person = ME, amount = amount, settled = amount))
        }
    }

    /** Income typed in by hand (salary, rent, gift…). It counts as income straight away. */
    suspend fun addHandIncome(ctx: Context, title: String, amount: Double, accountKey: String, category: String, at: Long) {
        val base = Entries.received(accountKey, title.ifBlank { category.ifBlank { "Added by me" } }, amount, at)
        Db.get(ctx).dao().insert(base.copy(status = "RECEIVED", note = "Received: " + category.ifBlank { "Income" }))
    }

    /**
     * Marks one message as a move between your own accounts. If the other half is waiting in the Inbox
     * (same amount, within a few days, other account) both are linked. Returns true when the other half was found.
     */
    suspend fun markOwnTransfer(ctx: Context, t: Txn): Boolean {
        val db = Db.get(ctx); val dao = db.dao()
        return db.withTransaction {
            val other = if (t.kind == "UPI_OUT") "UPI_IN" else "UPI_OUT"
            val mate = TransferMatcher.find(t, dao.transferCandidates(other, t.amount - 0.011, t.amount + 0.011,
                t.smsTime - Entries.DAYS3, t.smsTime + Entries.DAYS3), Entries.DAYS3)
            dao.setStatusNote(t.id, "SELF", Entries.OWN)
            mate?.let { dao.setStatusNote(it.id, "SELF", Entries.OWN) }
            mate != null
        }
    }

    // ---------- card bills ----------
    /** Stores a statement as a bill to pay. Returns true when it is new. */
    suspend fun ingestBill(ctx: Context, b: ParsedBill, smsTime: Long): Boolean {
        val db = Db.get(ctx); val dao = db.dao()
        return db.withTransaction {
            val all = dao.allBills()
            if (all.any { Bills.isSame(it, b) }) return@withTransaction false
            Bills.superseded(all, b).forEach { dao.setBillPaid(it.id, true) }
            // already paid? an earlier payment for this card after the statement date settles it at once
            dao.insertBill(Bill(bank = b.bank, card = b.card, total = b.total, minDue = b.minDue, dueDate = b.due, smsTime = smsTime))
            true
        }
    }
    suspend fun setBillPaid(ctx: Context, id: Long, paid: Boolean) = Db.get(ctx).dao().setBillPaid(id, paid)

    // ---------- accounts ----------
    /** A bank message showed the real balance: restart that account's running balance from it. Returns true if used. */
    suspend fun ingestBalance(ctx: Context, hit: BalanceHit, smsTime: Long): Boolean {
        val dao = Db.get(ctx).dao()
        val key = Accounts.keyOf(hit.bank, hit.last)
        val saved = dao.acctRulesList().mapNotNull { Accounts.decode(it) }.firstOrNull { it.key == key }
        val known = saved != null || dao.detectedAccounts().any { Accounts.keyOf(it.bank, it.card) == key }
        if (!known) return false
        if (saved != null && smsTime + 1 <= saved.asOf) return false      // older than what we already have
        dao.putRule(Accounts.encode(Account(key, hit.bank, hit.last, saved?.name ?: "", hit.balance, smsTime + 1)))
        return true
    }

    suspend fun setAccountBalance(ctx: Context, key: String, name: String, balance: Double) {
        val bank = key.substringBefore("|"); val last = key.substringAfter("|")
        Db.get(ctx).dao().putRule(Accounts.encode(Account(key, bank, last, name.trim(), balance, System.currentTimeMillis())))
    }

    // ---------- backup ----------
    suspend fun exportBackup(ctx: Context): String {
        val dao = Db.get(ctx).dao()
        // every setting you have made: UPI id, tags, saved phone numbers, groups, start date, streak, theme…
        val all = prefs(ctx).all
        val prefs = all.entries.filter { it.value is String && Backup.keep(it.key) && (it.value as String).isNotEmpty() }.associate { it.key to it.value as String }
        val flags = all.entries.filter { it.value is Boolean && Backup.keep(it.key) }.associate { it.key to it.value as Boolean }
        val data = BackupData(dao.allTxnsList(), dao.allSharesList(), dao.allSettlementsList(), dao.allRulesList(), prefs, dao.allBills(), dao.allPayables(), flags)
        val json = Backup.toJson(data, System.currentTimeMillis())
        // read it back before trusting it: a backup that cannot be read is worse than none
        val back = Backup.fromJson(json)
        check(back != null && back.txns.size == data.txns.size && back.shares.size == data.shares.size &&
            back.rules.size == data.rules.size && back.settlements.size == data.settlements.size && back.prefs == data.prefs && back.flags == data.flags) { "Backup check failed" }
        putPref(ctx, "lastBackup", System.currentTimeMillis().toString())
        return json
    }

    /** Replaces everything with the backup. Returns the number of transactions restored, or -1 if the file is not a backup. */
    suspend fun restoreBackup(ctx: Context, text: String): Int {
        val d = Backup.fromJson(text) ?: return -1
        val db = Db.get(ctx); val dao = db.dao()
        // a safety copy of what is on the phone now, in case the file turns out to be the wrong one
        try { java.io.File(ctx.filesDir, "before_restore.json").writeText(exportBackup(ctx)) } catch (_: Exception) {}
        db.withTransaction {
            dao.wipeShares(); dao.wipeSettlements(); dao.wipeRules(); dao.wipeTxns(); dao.wipeBills(); dao.wipePayables()
            d.txns.forEach { dao.restoreTxn(it) }
            d.shares.forEach { dao.restoreShare(it) }
            d.settlements.forEach { dao.restoreSettlement(it) }
            d.rules.forEach { dao.putRule(it) }
            d.bills.forEach { dao.restoreBill(it) }
            d.payables.forEach { dao.restorePayable(it) }
            // all of it must be there, or nothing changes
            check(dao.allTxnsList().size == d.txns.size && dao.allSharesList().size == d.shares.size &&
                dao.allSettlementsList().size == d.settlements.size && dao.allRulesList().size == d.rules.size) { "Restore check failed" }
        }
        prefs(ctx).edit().apply {
            d.prefs.forEach { (k, v) -> putString(k, v) }
            d.flags.forEach { (k, v) -> putBoolean(k, v) }
            // an older backup has no start date: use the oldest restored message, and do not show the welcome again
            if (d.prefs["trackFrom"].isNullOrEmpty() && getPref(ctx, "trackFrom", "").isEmpty())
                putString("trackFrom", (d.txns.minOfOrNull { it.smsTime } ?: System.currentTimeMillis()).toString())
            putBoolean("imported2", true)
        }.apply()
        return d.txns.size
    }

    // ---------- system access ----------
    fun hasPermission(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Reads existing messages from the inbox and ingests transactions. Safe to run repeatedly. */
    suspend fun importInbox(ctx: Context, since: Long): Int = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx, Manifest.permission.READ_SMS)) return@withContext 0
        var added = 0
        val cursor = ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("body", "date", "address"),
            "date > ?", arrayOf(since.toString()), "date ASC"
        ) ?: return@withContext 0
        cursor.use {
            val bi = it.getColumnIndex("body")
            val di = it.getColumnIndex("date")
            val ai = it.getColumnIndex("address")
            while (it.moveToNext()) {
                val text = it.getString(bi)
                val from = if (ai >= 0) it.getString(ai) else null
                val bill = BillParser.parse(text)
                if (bill != null) { ingestBill(ctx, bill, it.getLong(di)); continue }
                val p = SmsParser.parse(text, from)
                if (p != null && ingest(ctx, p, it.getLong(di), text ?: "") != null) added++
                val bal = BalanceSms.parse(text, from)
                if (bal != null) ingestBalance(ctx, bal, it.getLong(di))
                else if (p == null && Diagnostics.looksFinancial(text)) noteUnparsed(ctx, text ?: "")
            }
        }
        added
    }

    /** A payment seen in a notification (UPI app, bank app, messages app, mail preview). */
    suspend fun ingestNotification(ctx: Context, pkg: String, title: String, text: String, postedAt: Long): Txn? {
        val r = NotificationReader.read(pkg, title, text) ?: return null
        val at = if (postedAt > 0) postedAt else System.currentTimeMillis()
        r.bill?.let { ingestBill(ctx, it, at) }
        r.balance?.let { ingestBalance(ctx, it, at) }
        val p = r.parsed ?: return null
        val t = ingest(ctx, p, at, r.body)
        if (t != null && System.currentTimeMillis() - at < 2 * 86_400_000L) notifyNew(ctx, t)
        return t
    }

    fun notificationAccessOn(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Looks at the last 90 days of messages without saving anything, to say why some may not show up. */
    suspend fun readCheck(ctx: Context): Diagnostics.ReadCheck = withContext(Dispatchers.IO) {
        val sms = hasPermission(ctx, Manifest.permission.READ_SMS)
        val recv = hasPermission(ctx, Manifest.permission.RECEIVE_SMS)
        val notif = Build.VERSION.SDK_INT < 33 || hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
        var total = 0; var scanned = 0; var good = 0; var bad = 0
        val senders = linkedMapOf<String, Int>()
        if (sms) try {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id"), null, null, null)?.use { total = it.count }
            val since = System.currentTimeMillis() - 90 * 86_400_000L
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("body", "address"), "date > ?", arrayOf(since.toString()), "date DESC")?.use { c ->
                val bi = c.getColumnIndex("body"); val ai = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    scanned++
                    val text = c.getString(bi) ?: continue
                    val from = if (ai >= 0) c.getString(ai) else null
                    if (BillParser.parse(text) != null || SmsParser.parse(text, from) != null) good++
                    else if (Diagnostics.looksFinancial(text)) { bad++; Banks.senderCode(from).takeIf { it.isNotEmpty() }?.let { s -> senders[s] = (senders[s] ?: 0) + 1 } }
                }
            }
        } catch (e: Exception) { CrashLog.record(ctx, e) }
        Diagnostics.ReadCheck(sms, recv, notif, total, scanned, good, bad, senders.entries.sortedByDescending { it.value }.take(5).map { it.key }, Build.VERSION.SDK_INT, notificationAccessOn(ctx))
    }

    suspend fun loadContacts(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) return@withContext emptyList()
        val out = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        val c = ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null, null, ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        ) ?: return@withContext emptyList()
        c.use { while (it.moveToNext()) { val n = it.getString(0); if (!n.isNullOrBlank()) out.add(n.trim()) } }
        out.toList()
    }

    private const val CHANNEL = "new_txn"

    fun notifyNew(ctx: Context, t: Txn) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New transactions", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, t.id.toInt(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_TXN, t.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (t.status == "REPAID") {
            val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("${money(t.amount)} received ✓").setContentText(t.note).setContentIntent(open).setAutoCancel(true).build()
            nm.notify(t.id.toInt(), n); return
        }
        val incoming = t.kind == "UPI_IN" || t.kind == "REFUND"
        val title = if (incoming) "${money(t.amount)} received from ${t.merchant}" else "${money(t.amount)} at ${t.merchant}"
        val text = if (incoming) "${acct(t.bank, t.card)}: was this a repayment or a refund? Tap to match."
        else "${acct(t.bank, t.card)}: whose spend was this? Tap to tag."
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(t.id.toInt(), n)
    }
}
