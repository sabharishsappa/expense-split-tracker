package com.sabharish.splittracker

/**
 * Anything the app creates by itself (read from a message or a notification) is a draft until you tick it.
 * Drafts are shown, but they are not counted in your spending, income or budgets until confirmed.
 */
object Drafts {
    fun isDraft(t: Txn) = t.status == "INBOX" || (t.status == "TAGGED" && t.auto)
    fun isSpendDraft(t: Txn) = isDraft(t) && (t.kind == "SPEND" || t.kind == "UPI_OUT")
}
