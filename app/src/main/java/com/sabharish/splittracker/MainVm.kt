package com.sabharish.splittracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainVm(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    /** Any failure inside a background job is recorded instead of crashing the app. */
    private val safe = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashLog.record(getApplication(), e); status.value = "Something went wrong — details in Settings" }
    private fun <T> Flow<T>.hot(init: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.Eagerly, init)

    val inbox = dao.inbox().hot(emptyList())
    val moneyIn = dao.moneyIn().hot(emptyList())
    val all = dao.all().hot(emptyList())
    val shares = dao.shares().hot(emptyList())
    val pending = dao.pending().hot(emptyList())
    val summary = dao.summary().hot(emptyList())
    val recents = dao.recentPeople().hot(emptyList())
    val autoTagged = dao.autoTagged().hot(emptyList())
    val failed = dao.failed().hot(emptyList())
    val aliases = dao.aliases().map { list -> list.associate { it.ruleKey.removePrefix("alias|") to it.people } }.hot(emptyMap())

    val customTags = MutableStateFlow(Logic.parseTags(Repo.getPref(app, "tags", "")))
    val hiddenTags = MutableStateFlow(Logic.parseTags(Repo.getPref(app, "hiddenTags", "")))
    val tags: StateFlow<List<String>> = kotlinx.coroutines.flow.combine(customTags, hiddenTags, dao.tagCounts()) { c, h, n ->
        Logic.orderByUse(Logic.allTags(c, h), n.associate { it.category to it.n }) }
        .hot(Logic.allTags(emptyList()))

    fun renameTag(old: String, new: String) {
        val after = Logic.renameTag(customTags.value, old, new) ?: run { status.value = "That name is empty or already used"; return }
        customTags.value = after; Repo.putPref(getApplication(), "tags", Logic.joinTags(after))
        viewModelScope.launch(safe) { Repo.renameTag(getApplication(), old, new.replace(",", " ").trim().take(24)) }
    }
    fun deleteTag(name: String) {
        customTags.value = customTags.value.filter { !it.equals(name, true) }
        Repo.putPref(getApplication(), "tags", Logic.joinTags(customTags.value))
        viewModelScope.launch(safe) { Repo.deleteTag(getApplication(), name) }
    }
    fun toggleHidden(name: String) {
        hiddenTags.value = if (name in hiddenTags.value) hiddenTags.value - name else hiddenTags.value + name
        Repo.putPref(getApplication(), "hiddenTags", Logic.joinTags(hiddenTags.value))
    }

    fun addTag(name: String): String? {
        val before = customTags.value
        val after = Logic.addTag(before, name)
        if (after != before) { customTags.value = after; Repo.putPref(getApplication(), "tags", Logic.joinTags(after)) }
        return after.lastOrNull()?.takeIf { it.equals(name.replace(",", " ").trim().take(24), true) }
    }

    val accounts = kotlinx.coroutines.flow.combine(dao.all(), dao.acctRules()) { t, r -> Accounts.views(t, r) }.hot(emptyList())
    fun setBalance(key: String, name: String, balance: Double) =
        viewModelScope.launch(safe) { Repo.setAccountBalance(getApplication(), key, name, balance) }

    fun bulkApply(txns: List<Txn>, tag: String, people: List<String>) = viewModelScope.launch(safe) {
        Repo.bulkApply(getApplication(), txns, tag, people)
        status.value = "Updated ${txns.size} ${if (txns.size == 1) "bill" else "bills"}"
    }
    fun addFromStatement(s: StmtSource, l: StmtLine) = viewModelScope.launch(safe) { Repo.addFromStatement(getApplication(), s, l) }

    /** Set when a notification is tapped: which transaction to open. */
    val openId = MutableStateFlow(0L)

    /** False until the first read of the database finishes, so screens never flash "all caught up" while loading. */
    val loaded = MutableStateFlow(false)
    init { viewModelScope.launch(safe) { dao.all().first(); loaded.value = true } }
    fun undoAutoSettle(t: Txn) = viewModelScope.launch(safe) { Repo.undoAutoSettle(getApplication(), t); status.value = "Match undone — the payment is back in your Inbox" }
    suspend fun txnById(id: Long): Txn? = dao.txnById(id)

    val contacts = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("")

    init { viewModelScope.launch(safe) { Repo.backfillCategories(getApplication()) } }

    fun loadContacts() = viewModelScope.launch(safe) { contacts.value = Repo.loadContacts(getApplication()) }

    val dueBills = dao.dueBills().hot(emptyList())
    val payables = dao.payables().hot(emptyList())
    fun borrowed(t: Txn, person: String) = viewModelScope.launch(safe) { Repo.borrowed(getApplication(), t, person); status.value = "Noted — you owe $person ${Logic.money(t.amount)}" }
    fun receivedFor(t: Txn, reason: String) = viewModelScope.launch(safe) { Repo.receivedFor(getApplication(), t, reason) }
    fun addPayable(person: String, amount: Double) = viewModelScope.launch(safe) { Repo.addPayable(getApplication(), person, amount, "Added by hand") }
    /** A payment to one person clears their oldest debts first. */
    fun payPerson(list: List<Payable>, amount: Double) = viewModelScope.launch(safe) {
        var left = amount
        for (p in list.sortedBy { it.date }) {
            if (left <= 0.004) break
            val due = p.amount - p.paid
            val pay = Math.min(due, left)
            Repo.payPayable(getApplication(), p.id, pay); left -= pay
        }
    }
    fun payPayable(id: Long, amount: Double) = viewModelScope.launch(safe) { Repo.payPayable(getApplication(), id, amount) }
    fun billPaid(id: Long, paid: Boolean = true) = viewModelScope.launch(safe) { Repo.setBillPaid(getApplication(), id, paid) }

    fun importDays(days: Int) = importHistory(System.currentTimeMillis() - days * Bills.DAY)
    fun importHistory(since: Long, quiet: Boolean = false) = viewModelScope.launch(safe) {
        val n = Repo.importInbox(getApplication(), since)
        if (!quiet || n > 0) status.value = if (n > 0) "Imported $n new transactions" else "No new transactions found"
    }

    val lastBackup = MutableStateFlow(Repo.getPref(app, "lastBackup", "0").toLongOrNull() ?: 0L)

    suspend fun backupText(): String { val t = Repo.exportBackup(getApplication()); lastBackup.value = System.currentTimeMillis(); return t }

    fun restore(text: String) = viewModelScope.launch(safe) {
        val n = try { Repo.restoreBackup(getApplication(), text) } catch (e: IllegalStateException) {
            CrashLog.record(getApplication(), e); status.value = "Restore stopped — nothing on this phone was changed"; return@launch
        }
        if (n >= 0) {
            customTags.value = Logic.parseTags(Repo.getPref(getApplication(), "tags", ""))
            hiddenTags.value = Logic.parseTags(Repo.getPref(getApplication(), "hiddenTags", ""))
            ThemeState.mode = Repo.getPref(getApplication(), "theme", ThemeState.mode)
        }
        status.value = if (n >= 0) "Restored $n transactions, tags, people and settings" else "That file is not a Split Tracker backup"
    }

    fun clearStatus() { status.value = "" }

    fun tag(t: Txn, shares: List<NewShare>, category: String, remember: Boolean, splitAdded: Boolean) =
        viewModelScope.launch(safe) { Repo.tag(getApplication(), t, shares, category, remember, splitAdded) }

    fun tagAllMine(list: List<Txn>) = viewModelScope.launch(safe) { Repo.tagAllMine(getApplication(), list) }
    fun untag(id: Long) = viewModelScope.launch(safe) { Repo.untag(getApplication(), id) }
    fun addSpent(title: String, amount: Double, key: String) = viewModelScope.launch(safe) {
        Repo.addManual(getApplication(), title, amount, key); status.value = "Added — find it in your Inbox to tag it"
    }
    fun addReceived(title: String, amount: Double, key: String) = viewModelScope.launch(safe) {
        Repo.addReceived(getApplication(), title, amount, key); status.value = "Added — say what it was in your Inbox"
    }
    fun addMoved(from: AccountView, to: AccountView, amount: Double) = viewModelScope.launch(safe) {
        Repo.addMoved(getApplication(), from.key, to.key, from.label, to.label, amount)
        status.value = "Moved ${Logic.money(amount)} from ${from.label} to ${to.label}"
    }
    fun ownTransfer(t: Txn) = viewModelScope.launch(safe) {
        val both = Repo.markOwnTransfer(getApplication(), t)
        status.value = if (both) "Own transfer — both sides linked" else "Own transfer — left out of your spends"
    }
    fun ignore(t: Txn) = viewModelScope.launch(safe) { Repo.ignore(getApplication(), t) }
    fun setCategory(t: Txn, c: String) = viewModelScope.launch(safe) {
        val n = Repo.setCategory(getApplication(), t, c)
        if (n > 0) status.value = "Tagged $c · also applied to $n similar from ${Logic.nameOnly(t.merchant, aliases.value)}"
    }
    val tagRules = dao.catRules().hot(emptyList())
    fun removeTagRule(key: String) = viewModelScope.launch(safe) { Repo.removeTagRule(getApplication(), key) }
    fun settleShares(person: String, ids: Set<Long>, mode: String) =
        viewModelScope.launch(safe) { Repo.settleShares(getApplication(), person, ids, mode) }
    fun settle(person: String, amount: Double, mode: String) =
        viewModelScope.launch(safe) { Repo.settle(getApplication(), person, amount, mode) }
    fun repaidBy(t: Txn, person: String) = viewModelScope.launch(safe) { Repo.repaidBy(getApplication(), t, person) }
    fun linkRefund(refundId: Long, originalId: Long) = viewModelScope.launch(safe) { Repo.linkRefund(getApplication(), refundId, originalId) }
    fun keepAsRefund(id: Long) = viewModelScope.launch(safe) { Repo.keepAsRefund(getApplication(), id) }
    fun markFailed(t: Txn) = viewModelScope.launch(safe) {
        val closed = Repo.markFailed(getApplication(), t)
        status.value = if (closed) "Marked failed. A matching refund was already received, so it is closed." else "Marked failed. It closes automatically when the refund arrives."
    }
    fun unfail(id: Long) = viewModelScope.launch(safe) { Repo.unfail(getApplication(), id) }
    fun setAlias(raw: String, name: String) = viewModelScope.launch(safe) { Repo.setAlias(getApplication(), raw, name) }
    fun toggleSplitAdded(t: Txn) = viewModelScope.launch(safe) { dao.setSplitAdded(t.id, !t.splitAdded) }

    suspend fun suggestRefund(t: Txn): Txn? = Repo.suggestOriginal(getApplication(), t)
    suspend fun sharesFor(id: Long): List<Share> = dao.sharesFor(id)
    suspend fun suggestion(t: Txn): Rule? = Repo.suggestion(getApplication(), t)
    suspend fun recentSpends(refundAmount: Double): List<Txn> = Repo.recentSpends(getApplication(), refundAmount)
}
