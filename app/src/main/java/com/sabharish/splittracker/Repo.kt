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
    const val ME = "Me"

    // ---------- formatting ----------
    fun money(v: Double): String {
        val whole = Math.abs(v - Math.round(v)) < 0.005
        return "₹" + String.format(Locale("en", "IN"), if (whole) "%,.0f" else "%,.2f", v)
    }

    private val shortDate = SimpleDateFormat("d MMM", Locale.ENGLISH)
    fun shortDate(ms: Long): String = shortDate.format(Date(ms))

    private val bankNames = mapOf(
        "SBI" to "SBI", "HDFC" to "HDFC", "ICICI" to "ICICI", "INDUSIND" to "IndusInd",
        "YES" to "YES Bank", "KOTAK" to "Kotak", "FEDERAL" to "Federal"
    )

    /** "SBI ••6674" for cards/accounts with digits, plain bank name otherwise. */
    fun acct(bank: String, card: String): String {
        val b = bankNames[bank] ?: bank
        return if (card.isNotEmpty() && card.all { it.isDigit() }) "$b ••$card" else b
    }

    // ---------- keys ----------
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    fun merchantKey(merchant: String, card: String) = norm(merchant) + "|" + card
    fun catKey(merchant: String) = "cat|" + norm(merchant)

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
    suspend fun ingest(ctx: Context, p: Parsed, smsTime: Long): Txn? {
        val db = Db.get(ctx)
        val dao = db.dao()
        return db.withTransaction {
            val dup = dao.near(p.kind, p.amount, p.card, p.merchant, smsTime - 180_000, smsTime + 180_000)
            if (dup > 0) return@withTransaction null
            val isUpi = p.kind.startsWith("UPI")
            var status = when (p.kind) {
                "SPEND", "UPI_OUT", "UPI_IN", "REFUND" -> "INBOX"
                else -> "BILLPAY"
            }
            if (isUpi && isSelf(ctx, p.merchant)) status = "SELF"
            else if (p.kind == "UPI_IN" && p.amount < 10) status = "IGNORED"

            var cat = ""
            if (p.kind == "SPEND" || p.kind == "UPI_OUT") {
                cat = dao.rule(catKey(p.merchant))?.people ?: Categories.guess(p.merchant)
            }
            val t = Txn(
                bank = p.bank, card = p.card, kind = p.kind, amount = p.amount, merchant = p.merchant,
                txnDate = p.dateMillis ?: smsTime, smsTime = smsTime, status = status, category = cat
            )
            val id = dao.insert(t)
            var saved = t.copy(id = id)

            if ((p.kind == "SPEND" || p.kind == "UPI_OUT") && status == "INBOX") {
                val rule = dao.rule(merchantKey(p.merchant, p.card))
                if (rule != null && rule.action == "MINE") {
                    dao.insertShare(Share(txnId = id, person = ME, amount = p.amount, settled = p.amount))
                    dao.setStatus(id, "TAGGED")
                    saved = saved.copy(status = "TAGGED")
                }
            }
            if (p.kind == "REFUND") {
                val orig = findOriginal(dao, saved)
                if (orig != null) {
                    applyRefund(dao, saved, orig)
                    saved = saved.copy(status = "REFUND")
                }
            }
            if (saved.status == "INBOX") saved else null
        }
    }

    // ---------- refunds ----------
    private fun alnum4(s: String) = norm(s).take(4)

    private suspend fun findOriginal(dao: AppDao, refund: Txn): Txn? {
        val from = refund.smsTime - 120L * 86_400_000L
        val c = dao.refundCandidates(refund.amount - 0.011, refund.amount + 0.011, from, refund.smsTime)
        if (refund.merchant == "UPI reversal") return c.firstOrNull()
        return c.firstOrNull { alnum4(it.merchant) == alnum4(refund.merchant) }
    }

    private suspend fun applyRefund(dao: AppDao, refund: Txn, orig: Txn) {
        val ratio = minOf(1.0, refund.amount / orig.amount)
        for (s in dao.sharesFor(orig.id)) {
            dao.setShareAmount(s.id, Math.round(s.amount * (1 - ratio) * 100) / 100.0)
        }
        if (orig.status == "INBOX" && ratio > 0.995) dao.setStatus(orig.id, "REFUNDED")
        dao.setRefund(refund.id, orig.id, "REFUND", "Refund for ${orig.merchant}")
    }

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
    fun equalShares(total: Double, names: List<String>, paidOthers: Boolean): List<NewShare> {
        val ordered = names.filter { it == ME } + names.filter { it != ME }
        val n = ordered.size
        if (n == 0) return emptyList()
        val per = Math.floor(total / n * 100) / 100
        var left = total
        return ordered.mapIndexed { i, p ->
            val a = if (i == n - 1) Math.round(left * 100) / 100.0 else per
            left -= a
            NewShare(p, a, p == ME || paidOthers)
        }
    }

    suspend fun tag(ctx: Context, t: Txn, shares: List<NewShare>, category: String, remember: Boolean, splitAdded: Boolean) {
        val db = Db.get(ctx); val dao = db.dao()
        db.withTransaction {
            for (s in shares) {
                dao.insertShare(Share(txnId = t.id, person = s.person, amount = s.amount, settled = if (s.paid) s.amount else 0.0))
            }
            dao.setStatus(t.id, "TAGGED")
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
    suspend fun setCategory(ctx: Context, t: Txn, c: String) {
        val dao = Db.get(ctx).dao()
        dao.setCategory(t.id, c)
        if (c.isNotBlank()) dao.putRule(Rule(catKey(t.merchant), "CAT", c))
    }

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

    /** A received UPI payment is a repayment from [person]. */
    suspend fun repaidBy(ctx: Context, t: Txn, person: String) {
        val db = Db.get(ctx)
        db.withTransaction {
            applySettlement(db.dao(), person, t.amount, "UPI")
            db.dao().setStatusNote(t.id, "REPAID", "Repayment from $person")
        }
    }

    /** Best matching known person for a UPI sender name (e.g. "KURUVA KOUSHIK" ~ "Koushik K"). */
    fun bestMatch(name: String, people: List<String>): String? {
        fun toks(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.length >= 3 }
        val nt = toks(name)
        if (nt.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for (p in people) {
            val pt = toks(p)
            val score = nt.count { a ->
                pt.any { b -> a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) }
            }
            if (score > bestScore) { bestScore = score; best = p }
        }
        return best
    }

    // ---------- reminder text ----------
    fun reminderText(ctx: Context, person: String, rows: List<PendingRow>): String {
        val sorted = rows.sortedBy { it.txnDate }
        val total = sorted.sumOf { it.amount - it.settled }
        val days = ((System.currentTimeMillis() - sorted.first().txnDate) / 86_400_000L).toInt()
        val first = person.trim().substringBefore(" ")
        val intro = when {
            days <= 14 -> "Hi $first 👋\nA quick reminder about the spends I covered for you:"
            days <= 30 -> "Hi $first,\nGentle reminder about the spends I covered for you:"
            else -> "Hi $first,\nFollowing up on the spends I covered for you. This has been pending for over a month:"
        }
        val shown = sorted.take(8)
        val lines = shown.joinToString("\n") { "• ${shortDate(it.txnDate)} · ${it.merchant} · ${money(it.amount - it.settled)}" }
        val more = if (sorted.size > shown.size) "\n…and ${sorted.size - shown.size} more" else ""
        val upi = getPref(ctx, "upi", "").trim()
        val pay = if (upi.isNotEmpty()) "\nUPI: $upi" else ""
        val close = if (days > 30) "\nCould you please clear it this week? Thank you!" else "\nThank you!"
        return "$intro\n\n$lines$more\n\nTotal pending: ${money(total)}$pay$close"
    }

    // ---------- system access ----------
    fun hasPermission(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Reads existing messages from the inbox and ingests transactions. Safe to run repeatedly. */
    suspend fun importInbox(ctx: Context, days: Int): Int = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx, Manifest.permission.READ_SMS)) return@withContext 0
        val since = System.currentTimeMillis() - days * 86_400_000L
        var added = 0
        val cursor = ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("body", "date"),
            "date > ?", arrayOf(since.toString()), "date ASC"
        ) ?: return@withContext 0
        cursor.use {
            val bi = it.getColumnIndex("body")
            val di = it.getColumnIndex("date")
            while (it.moveToNext()) {
                val p = SmsParser.parse(it.getString(bi)) ?: continue
                if (ingest(ctx, p, it.getLong(di)) != null) added++
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
