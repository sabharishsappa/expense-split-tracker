package com.sabharish.splittracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.loadContacts()
        catchUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(
            arrayOf(
                Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS
            )
        )
        setContent { MaterialTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.loadContacts()
        catchUp()
    }

    /** Picks up anything the live receiver missed. First run reads the last 120 days. */
    private fun catchUp() {
        if (!Repo.hasPermission(this, Manifest.permission.READ_SMS)) return
        val sp = getSharedPreferences("st", Context.MODE_PRIVATE)
        val first = !sp.getBoolean("imported", false)
        vm.importHistory(if (first) 120 else 7, quiet = !first)
        sp.edit().putBoolean("imported", true).apply()
    }
}

private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
private fun d(ms: Long) = dateFmt.format(Date(ms))

@Composable
fun App(vm: MainVm) {
    var tab by remember { mutableIntStateOf(0) }
    val inbox by vm.inbox.collectAsState()
    val status by vm.status.collectAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Email, null) }, label = { Text("Inbox ${inbox.size}") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("Pending") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.List, null) }, label = { Text("All") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.DateRange, null) }, label = { Text("Summary") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(28.dp))
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            when (tab) {
                0 -> InboxScreen(vm)
                1 -> PendingScreen(vm)
                2 -> AllScreen(vm)
                else -> SummaryScreen(vm)
            }
        }
    }
}

@Composable
fun InboxScreen(vm: MainVm) {
    val items by vm.inbox.collectAsState()
    var dialogFor by remember { mutableStateOf<Txn?>(null) }
    var dialogMode by remember { mutableStateOf("OTHERS") }
    Text("Inbox: tag each card spend", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (items.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("All caught up. New card SMS appear here automatically.")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.importHistory(365) }) { Text("Import last 12 months of messages") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(Repo.money(t.amount), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${t.merchant}")
                    Text("${t.bank} ••${t.card}  ·  ${d(t.txnDate)}", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.tag(t, listOf(NewShare(Repo.ME, t.amount, true)), false) }) { Text("Mine") }
                        OutlinedButton(onClick = { dialogMode = "OTHERS"; dialogFor = t }) { Text("Others'") }
                        OutlinedButton(onClick = { dialogMode = "SPLIT"; dialogFor = t }) { Text("Split") }
                        TextButton(onClick = { vm.ignore(t) }) { Text("Ignore") }
                    }
                }
            }
        }
    }
    dialogFor?.let { t -> TagDialog(vm, t, dialogMode) { dialogFor = null } }
}

@Composable
fun TagDialog(vm: MainVm, t: Txn, initialMode: String, onDismiss: () -> Unit) {
    val contacts by vm.contacts.collectAsState()
    var mode by remember { mutableStateOf(initialMode) }
    val selected = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf(false) }
    var includeMe by remember { mutableStateOf(true) }
    var rememberRule by remember { mutableStateOf(true) }
    var sug by remember { mutableStateOf<Rule?>(null) }
    LaunchedEffect(t.id) { sug = vm.suggestion(t) }

    val q = query.trim()
    val filtered = contacts.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(40)
    val options = (if (q.isNotEmpty() && contacts.none { it.equals(q, true) }) listOf(q) else emptyList()) + filtered

    fun pick(name: String) {
        if (mode == "OTHERS") { selected.clear(); selected.add(name) }
        else if (selected.contains(name)) selected.remove(name) else selected.add(name)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Repo.money(t.amount)} at ${t.merchant}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mode == "OTHERS") Button(onClick = {}) { Text("Others'") }
                    else OutlinedButton(onClick = { mode = "OTHERS"; if (selected.size > 1) { val f = selected.first(); selected.clear(); selected.add(f) } }) { Text("Others'") }
                    if (mode == "SPLIT") Button(onClick = {}) { Text("Split") }
                    else OutlinedButton(onClick = { mode = "SPLIT" }) { Text("Split") }
                }
                val s = sug
                if (s != null && s.action == "PEOPLE" && s.people.isNotBlank()) {
                    TextButton(onClick = {
                        selected.clear(); selected.addAll(s.people.split(","))
                        mode = if (selected.size > 1) "SPLIT" else mode
                    }) { Text("Same as last time: ${s.people}") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(if (contacts.isEmpty()) "Type a name" else "Search contacts or type a name") },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.height(160.dp)) {
                    items(options) { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { pick(name) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = selected.contains(name), onCheckedChange = { pick(name) })
                            Text(if (name == q && contacts.none { it.equals(q, true) }) "Add \"$name\"" else name)
                        }
                    }
                }
                if (selected.isNotEmpty()) Text("Selected: ${selected.joinToString(", ")}", fontWeight = FontWeight.Bold)
                if (mode == "OTHERS") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = paid, onCheckedChange = { paid = it }); Text("Already paid me")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeMe, onCheckedChange = { includeMe = it }); Text("Include me in the split (equal shares)")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberRule, onCheckedChange = { rememberRule = it }); Text("Remember for this merchant and card")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    val shares = ArrayList<NewShare>()
                    if (mode == "OTHERS") {
                        shares.add(NewShare(selected[0], t.amount, paid))
                    } else {
                        val everyone = ArrayList<String>(selected)
                        if (includeMe) everyone.add(0, Repo.ME)
                        val per = Math.floor(t.amount / everyone.size * 100) / 100
                        var left = t.amount
                        everyone.forEachIndexed { i, p ->
                            val a = if (i == everyone.size - 1) Math.round(left * 100) / 100.0 else per
                            left -= a
                            shares.add(NewShare(p, a, p == Repo.ME))
                        }
                    }
                    vm.tag(t, shares, rememberRule)
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PendingScreen(vm: MainVm) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rows by vm.pending.collectAsState()
    var settleFor by remember { mutableStateOf<String?>(null) }
    val grouped = rows.groupBy { it.person }
    val total = rows.sumOf { it.amount - it.settled }
    Text("Pending: ${Repo.money(total)} owed to you", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (rows.isEmpty()) { Spacer(Modifier.height(12.dp)); Text("Nobody owes you anything right now.") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(grouped.entries.sortedByDescending { e -> e.value.sumOf { it.amount - it.settled } }.toList(), key = { it.key }) { e ->
            val owed = e.value.sumOf { it.amount - it.settled }
            val oldest = e.value.minOf { it.txnDate }
            val days = ((System.currentTimeMillis() - oldest) / 86_400_000L).toInt()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(e.key, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(Repo.money(owed), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${e.value.size} items · oldest $days days", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    e.value.forEach { r ->
                        Text("• ${r.merchant}  ${Repo.money(r.amount - r.settled)}  (${d(r.txnDate)})", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { settleFor = e.key }) { Text("Settle") }
                        OutlinedButton(onClick = { remind(ctx, e.key, e.value) }) { Text("Remind") }
                    }
                }
            }
        }
    }
    settleFor?.let { person ->
        val owed = rows.filter { it.person == person }.sumOf { it.amount - it.settled }
        var amt by remember { mutableStateOf(String.format(Locale.US, "%.2f", owed)) }
        var mode by remember { mutableStateOf("UPI") }
        AlertDialog(
            onDismissRequest = { settleFor = null },
            title = { Text("Settle with $person") },
            text = {
                Column {
                    Text("Clears the oldest items first. Partial payments are fine.")
                    OutlinedTextField(value = amt, onValueChange = { amt = it }, singleLine = true, label = { Text("Amount received") })
                    OutlinedTextField(value = mode, onValueChange = { mode = it }, singleLine = true, label = { Text("Mode (UPI / cash)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = amt.toDoubleOrNull()
                    if (v != null && v > 0) vm.settle(person, v, mode)
                    settleFor = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { settleFor = null }) { Text("Cancel") } }
        )
    }
}

private fun remind(ctx: Context, person: String, rows: List<PendingRow>) {
    val lines = rows.joinToString("\n") { "• ${it.merchant} ${Repo.money(it.amount - it.settled)} on ${d(it.txnDate)}" }
    val total = rows.sumOf { it.amount - it.settled }
    val text = "Hi $person, pending from my card spends:\n$lines\nTotal: ${Repo.money(total)}. Please send when you can. Thanks!"
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(i, "Send reminder").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun AllScreen(vm: MainVm) {
    val txns by vm.all.collectAsState()
    val shares by vm.shares.collectAsState()
    var query by remember { mutableStateOf("") }
    val byTxn = shares.groupBy { it.txnId }
    val q = query.trim()
    val shown = txns.filter { t ->
        q.isEmpty() || t.merchant.contains(q, true) || t.card.contains(q) || t.bank.contains(q, true) ||
            (byTxn[t.id] ?: emptyList()).any { it.person.contains(q, true) }
    }
    Text("All transactions", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("Filter by merchant, person, card or bank") }, modifier = Modifier.fillMaxWidth()
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown, key = { it.id }) { t ->
            val ss = byTxn[t.id] ?: emptyList()
            val label = when (t.kind) { "REFUND" -> "Refund"; "PAYMENT" -> "Bill payment"; else -> t.status.lowercase(Locale.ROOT) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.merchant, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(Repo.money(t.amount), fontWeight = FontWeight.Bold)
                    }
                    Text("${t.bank} ••${t.card} · ${d(t.txnDate)} · $label", fontSize = 12.sp)
                    ss.forEach { s ->
                        val state = if (s.person == Repo.ME) "mine" else if (s.settled >= s.amount - 0.005) "paid" else "owes ${Repo.money(s.amount - s.settled)}"
                        Text("${s.person}: ${Repo.money(s.amount)} ($state)", fontSize = 12.sp)
                    }
                    if (ss.any { it.person != Repo.ME }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = t.splitAdded, onCheckedChange = { vm.toggleSplitAdded(t) })
                            Text("Added to PhonePe Split", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(vm: MainVm) {
    val rows by vm.summary.collectAsState()
    val receivable = rows.filter { it.person != Repo.ME }.sumOf { it.shareAmt - it.settled }
    val monthKey = SimpleDateFormat("yyyy-MM", Locale.ENGLISH)
    val monthLabel = SimpleDateFormat("MMM yyyy", Locale.ENGLISH)
    val grouped = rows.groupBy { monthKey.format(Date(it.txnDate)) + "|" + it.card }
    val keys = grouped.keys.sortedDescending()
    Text("Summary", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Total receivable from others: ${Repo.money(receivable)}", fontSize = 16.sp)
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(keys) { k ->
            val g = grouped[k] ?: emptyList()
            val mine = g.filter { it.person == Repo.ME }.sumOf { it.shareAmt }
            val others = g.filter { it.person != Repo.ME }.sumOf { it.shareAmt }
            val owed = g.filter { it.person != Repo.ME }.sumOf { it.shareAmt - it.settled }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${monthLabel.format(Date(g.first().txnDate))} · card ••${g.first().card}", fontWeight = FontWeight.Bold)
                    Text("Total ${Repo.money(mine + others)}  ·  Mine ${Repo.money(mine)}  ·  Others ${Repo.money(others)}", fontSize = 13.sp)
                    if (owed > 0.005) Text("Still to recover: ${Repo.money(owed)}", fontSize = 13.sp)
                }
            }
        }
    }
}
