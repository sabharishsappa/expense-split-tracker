package com.sabharish.splittracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainVm(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(init: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, init)

    val inbox = dao.inbox().hot(emptyList())
    val all = dao.all().hot(emptyList())
    val shares = dao.shares().hot(emptyList())
    val pending = dao.pending().hot(emptyList())
    val summary = dao.summary().hot(emptyList())
    val settlements = dao.settlements().hot(emptyList())

    val contacts = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("")

    fun loadContacts() = viewModelScope.launch { contacts.value = Repo.loadContacts(getApplication()) }

    fun importHistory(days: Int, quiet: Boolean = false) = viewModelScope.launch {
        val n = Repo.importInbox(getApplication(), days)
        if (!quiet || n > 0) status.value = if (n > 0) "Imported $n new transactions" else "No new transactions found"
    }

    fun tag(t: Txn, shares: List<NewShare>, remember: Boolean) =
        viewModelScope.launch { Repo.tag(getApplication(), t, shares, remember) }

    fun ignore(t: Txn) = viewModelScope.launch { Repo.ignore(getApplication(), t) }

    fun settle(person: String, amount: Double, mode: String) =
        viewModelScope.launch { Repo.settle(getApplication(), person, amount, mode) }

    fun toggleSplitAdded(t: Txn) = viewModelScope.launch { dao.setSplitAdded(t.id, !t.splitAdded) }

    suspend fun suggestion(t: Txn): Rule? = Repo.suggestion(getApplication(), t)
}
