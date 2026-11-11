package com.sabharish.splittracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/** Fires once a day at 9 pm (if you turned it on), posts one quiet summary, and sets up the next one. Reads only this phone's data. */
class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (!isOn(app)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
                    val dao = Db.get(app).dao()
                    val txns = dao.allTxnsList()
                    val shares = dao.allSharesList().groupBy { it.txnId }
                    val c = Calendar.getInstance()
                    val (y, m, d) = Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                    val cal = Calendar.getInstance()
                    val today = txns.filter { t -> cal.timeInMillis = t.txnDate; cal.get(Calendar.YEAR) == y && cal.get(Calendar.MONTH) == m && cal.get(Calendar.DAY_OF_MONTH) == d }
                        .sumOf { Stats.expenseOf(it, shares[it.id] ?: emptyList()) }
                    Digest.message(txns.count { it.status == "INBOX" }, today)?.let { Repo.notifyDigest(app, it) }
                }
                schedule(app)
            } catch (e: Exception) { CrashLog.record(app, e) } finally { pending.finish() }
        }
    }

    companion object {
        fun isOn(ctx: Context) = Repo.getPref(ctx, Digest.KEY, "") == "1"

        private fun intent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 77, Intent(ctx, DigestReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!isOn(ctx)) { am.cancel(intent(ctx)); return }
            am.setAndAllowWhileIdle(AlarmManager.RTC, Digest.nextTrigger(System.currentTimeMillis()), intent(ctx))
        }

        fun cancel(ctx: Context) = (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent(ctx))
    }
}
