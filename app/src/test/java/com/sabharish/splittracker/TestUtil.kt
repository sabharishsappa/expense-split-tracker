package com.sabharish.splittracker

import java.util.Calendar

object TestUtil {
    fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, h, 0, 0)
        return c.timeInMillis
    }

    fun ymd(ms: Long?): String {
        if (ms == null) return "null"
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun tx(
        id: Long, merchant: String, amount: Double, sms: Long,
        status: String = "TAGGED", kind: String = "SPEND", bank: String = "SBI", card: String = "6674"
    ) = Txn(id = id, bank = bank, card = card, kind = kind, amount = amount, merchant = merchant,
        txnDate = sms, smsTime = sms, status = status)

    fun pending(
        person: String, merchant: String, amount: Double, date: Long,
        total: Double = amount, parts: Int = 1, settled: Double = 0.0
    ) = PendingRow(shareId = 1, txnId = 1, person = person, amount = amount, settled = settled,
        merchant = merchant, card = "6674", txnDate = date, txnTotal = total, parts = parts)
}
