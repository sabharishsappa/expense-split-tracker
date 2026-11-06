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

    private val shortDate = SimpleDateFormat("d MMM", Locale.ENGLISH)
    fun shortDate(ms: Long): String = shortDate.format(Date(ms))

    private val bankNames = mapOf(
        "SBI" to "SBI", "HDFC" to "HDFC", "ICICI" to "ICICI", "INDUSIND" to "IndusInd",
        "YES" to "YES Bank", "KOTAK" to "Kotak", "FEDERAL" to "Federal"
    )

    fun bankName(bank: String): String = bankNames[bank] ?: bank

    /** "SBI ••6674" for cards/accounts with digits, plain bank name otherwise. */
    fun acct(bank: String, card: String): String {
        val b = bankNames[bank] ?: bank
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
        getPref(ctx, "selfnames", "sabharish").split(",").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }

    private fun isSelf(ctx: Context, name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return selfNames(ctx).any { n.contains(it) }
    }

    // ---------- ingest ----------
    /** Stores one parsed SMS. Returns the new transaction when it needs the user's attention. */
    suspend fun ingest(ctx: Context, p: Parsed, smsTime: Long, body: String = ""): Txn? {
        val db = Db.get(ctx)
        val dao = db.dao()
        return db.withTransaction {
            val dup = dao.near(p.kind, p.amount, p.card, p.merchant, smsTime - 180_000, smsTime + 180_000)
            if (dup > 0) {
                if (body.isNotBlank()) dao.fillBody(p.kind, p.amount, p.card, p.merchant, smsTime - 180_000, smsTime + 180_000, body)
                return@withTransaction null
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
                val mine = (rule != null && rule.action == "MINE") || (rule == null && Categories.defaultMine(p.merchant))
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
            if (saved.status == "INBOX") saved else null
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

    suspend fun untag(ctx: Context, id: Long) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction { dao.deleteShares(id); dao.setStatus(id, "INBOX") }
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

    // ---------- reminder text ----------
    fun reminderText(ctx: Context, person: String, rows: List<PendingRow>, aliases: Map<String, String>): String =
        Reminder.build(person, rows, aliases, getPref(ctx, "upi", ""), System.currentTimeMillis())

    // ---------- statements ----------
    /** Adds a statement line the app never saw, as an untagged spend. */
    suspend fun addFromStatement(ctx: Context, s: StmtSource, l: StmtLine) {
        val kind = if (s.isCard) "SPEND" else "UPI_OUT"
        Db.get(ctx).dao().insert(Txn(bank = s.bank, card = s.last, kind = kind, amount = l.amount, merchant = Logic.title(l.desc.take(40), emptyMap()),
            txnDate = l.date, smsTime = l.date, status = "INBOX", note = "Added from statement"))
    }

    // ---------- cash lent ----------
    /** Money you handed over (cash or otherwise) that someone should give back. Shows under Owed as "Lent". */
    suspend fun addLent(ctx: Context, person: String, amount: Double, note: String = "") {
        val db = Db.get(ctx); val dao = db.dao()
        val now = System.currentTimeMillis()
        db.withTransaction {
            val id = dao.insert(Txn(bank = "CASH", card = "", kind = "UPI_OUT", amount = amount, merchant = "Lent to $person",
                txnDate = now, smsTime = now, status = "TAGGED", category = "Lent", note = note))
            dao.insertShare(Share(txnId = id, person = person, amount = amount, settled = 0.0))
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
        val known = saved != null || Accounts.detected(dao.allTxnsList()).any { Accounts.keyOf(it.first, it.second) == key }
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
        val prefs = Backup.PREF_KEYS.associateWith { getPref(ctx, it, "") }.filterValues { it.isNotEmpty() }
        val json = Backup.toJson(BackupData(dao.allTxnsList(), dao.allSharesList(), dao.allSettlementsList(), dao.allRulesList(), prefs, dao.allBills()), System.currentTimeMillis())
        putPref(ctx, "lastBackup", System.currentTimeMillis().toString())
        return json
    }

    /** Replaces everything with the backup. Returns the number of transactions restored, or -1 if the file is not a backup. */
    suspend fun restoreBackup(ctx: Context, text: String): Int {
        val d = Backup.fromJson(text) ?: return -1
        val dao = Db.get(ctx).dao()
        dao.wipeShares(); dao.wipeSettlements(); dao.wipeRules(); dao.wipeTxns(); dao.wipeBills()
        d.txns.forEach { dao.restoreTxn(it) }
        d.shares.forEach { dao.restoreShare(it) }
        d.settlements.forEach { dao.restoreSettlement(it) }
        d.rules.forEach { dao.putRule(it) }
        d.bills.forEach { dao.restoreBill(it) }
        d.prefs.forEach { (k, v) -> putPref(ctx, k, v) }
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
            Uri.parse("content://sms/inbox"), arrayOf("body", "date"),
            "date > ?", arrayOf(since.toString()), "date ASC"
        ) ?: return@withContext 0
        cursor.use {
            val bi = it.getColumnIndex("body")
            val di = it.getColumnIndex("date")
            while (it.moveToNext()) {
                val text = it.getString(bi)
                val bill = BillParser.parse(text)
                if (bill != null) { ingestBill(ctx, bill, it.getLong(di)); continue }
                val p = SmsParser.parse(text)
                if (p != null && ingest(ctx, p, it.getLong(di), text ?: "") != null) added++
                BalanceSms.parse(text)?.let { h -> ingestBalance(ctx, h, it.getLong(di)) }
            }
        }
        added
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
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
