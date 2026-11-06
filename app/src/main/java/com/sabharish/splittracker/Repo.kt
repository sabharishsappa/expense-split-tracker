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
import java.util.Locale

data class NewShare(val person: String, val amount: Double, val paid: Boolean)

object Repo {
    const val ME = "Me"

    fun merchantKey(merchant: String, card: String): String =
        merchant.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") + "|" + card

    fun money(v: Double): String = "₹" + String.format(Locale("en", "IN"), "%,.2f", v)

    /** Stores one parsed SMS. Returns the new transaction when it needs the user's attention. */
    suspend fun ingest(ctx: Context, p: Parsed, smsTime: Long): Txn? {
        val db = Db.get(ctx)
        val dao = db.dao()
        val status = when (p.kind) {
            "SPEND" -> "INBOX"
            "REFUND" -> "REFUND"
            else -> "BILLPAY"
        }
        return db.withTransaction {
            val dup = dao.near(p.kind, p.amount, p.card, p.merchant, smsTime - 180_000, smsTime + 180_000)
            if (dup > 0) return@withTransaction null
            val t = Txn(
                bank = p.bank, card = p.card, kind = p.kind, amount = p.amount, merchant = p.merchant,
                txnDate = p.dateMillis ?: smsTime, smsTime = smsTime, status = status
            )
            val id = dao.insert(t)
            var saved = t.copy(id = id)
            if (p.kind == "SPEND") {
                val rule = dao.rule(merchantKey(p.merchant, p.card))
                if (rule != null && rule.action == "MINE") {
                    dao.insertShare(Share(txnId = id, person = ME, amount = p.amount, settled = p.amount))
                    dao.setStatus(id, "TAGGED")
                    saved = saved.copy(status = "TAGGED")
                }
            }
            if (saved.status == "INBOX") saved else null
        }
    }

    suspend fun tag(ctx: Context, t: Txn, shares: List<NewShare>, remember: Boolean) {
        val db = Db.get(ctx)
        val dao = db.dao()
        db.withTransaction {
            for (s in shares) {
                dao.insertShare(Share(txnId = t.id, person = s.person, amount = s.amount, settled = if (s.paid) s.amount else 0.0))
            }
            dao.setStatus(t.id, "TAGGED")
            if (remember) {
                val onlyMe = shares.size == 1 && shares[0].person == ME
                val others = shares.filter { it.person != ME }.joinToString(",") { it.person }
                dao.putRule(Rule(merchantKey(t.merchant, t.card), if (onlyMe) "MINE" else "PEOPLE", others))
            }
        }
    }

    suspend fun ignore(ctx: Context, t: Txn) = Db.get(ctx).dao().setStatus(t.id, "IGNORED")

    suspend fun suggestion(ctx: Context, t: Txn): Rule? = Db.get(ctx).dao().rule(merchantKey(t.merchant, t.card))

    suspend fun settle(ctx: Context, person: String, amount: Double, mode: String) {
        val db = Db.get(ctx)
        val dao = db.dao()
        db.withTransaction {
            var left = amount
            for (s in dao.openShares(person)) {
                if (left <= 0.004) break
                val pay = minOf(s.amount - s.settled, left)
                dao.setSettled(s.id, s.settled + pay)
                left -= pay
            }
            dao.insertSettlement(Settlement(person = person, amount = amount, date = System.currentTimeMillis(), mode = mode))
        }
    }

    fun hasPermission(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Reads existing messages from the inbox and ingests card transactions. Safe to run repeatedly. */
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New card spends", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("${money(t.amount)} at ${t.merchant}")
            .setContentText("Card ••${t.card}: whose spend was this? Tap to tag.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(t.id.toInt(), n)
    }
}
