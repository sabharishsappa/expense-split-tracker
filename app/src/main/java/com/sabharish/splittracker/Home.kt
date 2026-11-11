package com.sabharish.splittracker

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One-glance home: what others owe you, what needs your attention. */
@Composable
fun HomeScreen(vm: MainVm, go: (Int) -> Unit) {
    val pending by vm.pending.collectAsState()
    val spends by vm.inbox.collectAsState()
    val money by vm.moneyIn.collectAsState()
    val failed by vm.failed.collectAsState()
    val owed = pending.filter { it.person != Repo.ME }.groupBy { it.person }
        .mapValues { e -> e.value.sumOf { it.amount - it.settled } }.filterValues { it > 0.004 }
    val accounts by vm.accounts.collectAsState()
    val allTxns by vm.all.collectAsState()
    val lastBackup by vm.lastBackup.collectAsState()
    val bills by vm.dueBills.collectAsState()
    val payables by vm.payables.collectAsState()
    val oweList = remember(payables) { Logic.owedByYou(payables) }
    var payingBill by remember { mutableStateOf<Bill?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    val total = owed.values.sum()
    val toReview = spends.size + money.size
    payingBill?.let { b ->
        AlertDialog(
            onDismissRequest = { payingBill = null },
            title = { Text("${Repo.bankName(b.bank)} ••${b.card}") },
            text = { Text("Total ${Logic.money(b.total)}${if (b.minDue > 0) " · minimum ${Logic.money(b.minDue)}" else ""}\nDue ${Repo.shortDate(b.dueDate)}\n\nPaid already? Payments made by SMS are matched automatically; mark it here if you paid another way.") },
            confirmButton = { TextButton(onClick = { vm.billPaid(b.id); payingBill = null }) { Text("Mark as paid") } },
            dismissButton = { TextButton(onClick = { payingBill = null }) { Text("Close") } }
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item { SmsBanner() }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val now = System.currentTimeMillis()
            val notes = listOfNotNull(Health.capture(allTxns, now), Health.backup(lastBackup, AutoBackup.folder(ctx) != null, allTxns.size, now))
            if (notes.isNotEmpty()) Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                notes.forEach { Text(if (it.warn) "⚠ ${it.text}" else it.text, fontSize = 12.sp, color = if (it.warn) amber() else muted()) }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF115E59))))
                    .clickable { go(2) }.padding(22.dp)
            ) {
                Text("Others owe you", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(Logic.money(total), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (owed.isEmpty()) "All settled 🎉" else "${owed.size} ${if (owed.size == 1) "person owes" else "people owe"} you",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp
                )
                val lent = Logic.lentTotal(pending)
                if (lent > 0.004) {
                    Spacer(Modifier.height(2.dp))
                    Text("Cash lent ${Logic.money(lent)} · Shared spends ${Logic.money(total - lent)}", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
        }
        item { MonthCard(vm) { go(4) } }
        if (bills.isNotEmpty()) item { BillsCard(bills) { b -> payingBill = b } }
        if (oweList.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).clickable { go(2) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("You owe people", fontSize = 13.sp, color = muted())
                    Text(Logic.money(oweList.sumOf { it.second }), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Text("${oweList.size} ${if (oweList.size == 1) "person" else "people"} ›", fontSize = 13.sp, color = muted())
            }
        }
        item {
            val all by vm.all.collectAsState()
            val now = System.currentTimeMillis()
            val clarity = Habit.clarity(all, Habit.monthStart(now))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var streak by remember { mutableStateOf(Habit.decode(Repo.getPref(ctx, "streak", ""))) }
            androidx.compose.runtime.LaunchedEffect(spends.size, money.size, all.size) {
                if (all.isNotEmpty()) {
                    val next = Habit.update(streak, System.currentTimeMillis(), spends.isEmpty() && money.isEmpty())
                    if (next != streak) { streak = next; Repo.putPref(ctx, "streak", Habit.encode(next)) }
                }
            }
            val days = Habit.shown(streak, now)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClarityRing(clarity ?: 1f)
                    Spacer(Modifier.padding(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("This month's clarity", fontSize = 13.sp, color = muted())
                        Text(Habit.clarityLine(spends.size), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (days > 0) "🔥 $days-day inbox-zero streak" else "Clear your inbox today to start a streak", fontSize = 13.sp, color = if (days > 0) amber() else muted())
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("To review", toReview.toString(), if (toReview > 0) amber() else green(), Modifier.weight(1f)) { go(1) }
                StatTile("Awaiting refund", failed.size.toString(), if (failed.isNotEmpty()) amber() else green(), Modifier.weight(1f)) { go(11) }
            }
        }
        if (accounts.isNotEmpty()) {
            item {
                val known = accounts.filter { it.hasBalance }
                Card(
                    Modifier.fillMaxWidth().clickable { showAccounts = true }, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("My accounts", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("${accounts.size} ${if (accounts.size == 1) "account" else "accounts"} · tap to see balances", fontSize = 12.sp, color = muted())
                        }
                        if (known.isNotEmpty()) Text(Logic.money(known.sumOf { it.balance }), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("  ›", fontSize = 22.sp, color = muted())
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth().clickable { checking = true }, shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Check a statement", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Upload a statement to find missing or extra payments", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (owed.isNotEmpty()) {
            item { Text("Who owes you", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
            owed.entries.sortedByDescending { it.value }.take(6).forEach { (p, amt) ->
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { go(2) }, shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p)
                            Spacer(Modifier.padding(6.dp))
                            Text(p, Modifier.weight(1f), fontSize = 16.sp)
                            Text(Logic.money(amt), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = amber())
                        }
                    }
                }
            }
        }
    }
    if (checking) StatementFlow(vm) { checking = false }
    if (showAccounts) AccountsDialog(vm) { showAccounts = false }
}

@Composable
private fun StatTile(label: String, value: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = tint)
            Text(label, fontSize = 13.sp, color = muted())
        }
    }
}

@Composable
internal fun BalanceDialog(a: AccountView, onDismiss: () -> Unit, onSave: (String, Double) -> Unit) {
    var amount by remember { mutableStateOf(if (a.hasBalance) a.balance.toString().removeSuffix(".0") else "") }
    var name by remember { mutableStateOf(a.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(a.label) },
        text = {
            Column {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, singleLine = true,
                    label = { Text("Balance right now (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name (optional)") })
                Spacer(Modifier.height(6.dp))
                Text("Updates automatically from your bank messages.", fontSize = 12.sp, color = muted())
            }
        },
        confirmButton = { TextButton(onClick = { amount.replace(",", "").toDoubleOrNull()?.let { onSave(name, it) } }) { Text("✓") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ClarityRing(fraction: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 9.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(track, -90f, 360f, false, style = stroke)
            drawArc(bar, -90f, 360f * fraction, false, style = stroke)
        }
        Text("${(fraction * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatementFlow(vm: MainVm, initial: String? = null, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val all by vm.all.collectAsState()
    val sources = remember(all.size) { Statement.sources(all) }
    var source by remember { mutableStateOf(sources.firstOrNull { it.key == initial }) }
    var closing by remember { mutableStateOf<Double?>(null) }
    var lastLine by remember { mutableStateOf(0L) }
    var balanceSet by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Recon?>(null) }
    var added by remember { mutableStateOf(setOf<StmtLine>()) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val s = source
        if (uri != null && s != null) scope.launch {
            message = "Reading…"
            try {
                val text = StatementReader.readText(ctx, uri, password)
                val lines = Statement.parse(text)
                if (lines.isEmpty()) message = "I couldn't find any transactions in that file. If it is a PDF, send me a sample so I can tune the reader."
                else { closing = Statement.closingBalance(text); lastLine = lines.maxOf { it.date }; result = Statement.reconcile(lines, Statement.txnsFor(s, all)); message = "" }
            } catch (e: StatementReader.NeedsPassword) {
                message = "Could not open it. If the PDF has a password, enter it above and choose the file again."
            } catch (e: Exception) { message = "Could not read that file." }
        }
    }
    val r = result
    if (r == null) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Check a statement") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("1. Which card or account?", fontWeight = FontWeight.SemiBold)
                    if (sources.isEmpty()) Text("Nothing to check yet.", fontSize = 13.sp, color = muted())
                    sources.forEach { s -> Pill(s.label, { source = s }, selected = source == s, modifier = Modifier.padding(top = 6.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Text("2. PDF password (if any)", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text("Used only to open the file on this phone. It is not saved.", fontSize = 12.sp, color = muted())
                    if (message.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(message, fontSize = 13.sp, color = amber()) }
                }
            },
            confirmButton = { TextButton(enabled = source != null, onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
        )
    } else {
        val s = source!!
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("${r.matched} of ${r.lines} lines match") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    val cl = closing
                    if (cl != null && !s.isCard) {
                        val accts by vm.accounts.collectAsState()
                        val view = accts.firstOrNull { it.key == Accounts.keyOf(s.bank, s.last) }
                        Text("Statement closing balance ${Logic.money(cl)}", fontWeight = FontWeight.SemiBold)
                        if (view != null && view.hasBalance) Text(Reconcile.summary(view.balance, cl), fontSize = 13.sp, color = muted())
                        if (Reconcile.isCurrent(lastLine, System.currentTimeMillis())) {
                            if (balanceSet) Text("Balance updated ✓", fontSize = 13.sp, color = green())
                            else TextButton(onClick = { vm.setBalance(Accounts.keyOf(s.bank, s.last), view?.name ?: "", cl, "statement"); balanceSet = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Use this as the account balance") }
                        } else Text("This statement ended ${Logic.shortDate(lastLine)}, so it can't tell today's balance.", fontSize = 12.sp, color = muted())
                        Spacer(Modifier.height(10.dp))
                    }
                    if (r.missing.isEmpty() && r.extra.isEmpty()) Text("Everything lines up 🎉")
                    if (r.missing.isNotEmpty()) {
                        Text("On the statement, not in the app (${r.missing.size})", fontWeight = FontWeight.SemiBold)
                        r.missing.forEach { l ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${Logic.shortDate(l.date)} · ${l.desc.take(28)} · ${Logic.money(l.amount)}${if (l.credit) " (credit)" else ""}", Modifier.weight(1f), fontSize = 13.sp)
                                if (!l.credit) {
                                    if (l in added) Text("Added", fontSize = 12.sp, color = green())
                                    else TextButton(onClick = { vm.addFromStatement(s, l); added = added + l }) { Text("Add") }
                                }
                            }
                        }
                        Text("Added lines land in your Inbox to tag.", fontSize = 12.sp, color = muted())
                    }
                    if (r.extra.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("In the app, not on the statement (${r.extra.size})", fontWeight = FontWeight.SemiBold)
                        r.extra.forEach { t -> Text("${Logic.shortDate(t.txnDate)} · ${t.merchant.take(28)} · ${Logic.money(t.amount)}", fontSize = 13.sp) }
                        Text("These may be pending on the bank side, or from another card.", fontSize = 12.sp, color = muted())
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDone) { Text("✓") } }
        )
    }
}


/** Card statements waiting to be paid, most urgent first. */
@Composable
private fun BillsCard(bills: List<Bill>, onTap: (Bill) -> Unit) {
    val now = System.currentTimeMillis()
    val total = bills.sumOf { it.total }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("You need to pay", fontSize = 13.sp, color = muted())
                Text(Logic.money(total), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Text("${bills.size} ${if (bills.size == 1) "bill" else "bills"}", fontSize = 13.sp, color = muted())
        }
        bills.forEach { b ->
            val days = Bills.daysLeft(b.dueDate, now)
            val c = when (Bills.urgency(days)) { "OVERDUE" -> MaterialTheme.colorScheme.error; "SOON" -> amber(); else -> green() }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onTap(b) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Repo.bankName(b.bank)} ••${b.card}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (b.minDue > 0) "Minimum ${Logic.money(b.minDue)}" else "Full amount", fontSize = 12.sp, color = muted())
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Logic.money(b.total), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${Repo.shortDate(b.dueDate)} · ${Bills.dueLine(days)}", fontSize = 12.sp, color = c)
                }
            }
        }
    }
}


/** This month at a glance: what you spent and, when a budget is set, how much is safe to spend today. */
@Composable
private fun MonthCard(vm: MainVm, open: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val txns by vm.all.collectAsState()
    val shareList by vm.shares.collectAsState()
    val shares = remember(shareList) { shareList.groupBy { it.txnId } }
    val ep = Epoch.value   // changes when the month start day is changed
    val (y, m) = Stats.monthOf(System.currentTimeMillis())
    val spent = remember(txns, shares, ep) { Stats.spentInMonth(txns, shares, y, m) }
    val limit = Budgets.decode(Repo.getPref(ctx, "budgets", ""))[Budgets.TOTAL]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = open).padding(18.dp)) {
        Text(if (Stats.startDay <= 1) "Spent in ${Stats.monthLabel(m)}" else "Spent · ${Stats.periodTitle(y, m)}", fontSize = 13.sp, color = muted())
        Text(Logic.money(spent), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (limit != null) {
            val st = Budgets.state(spent, limit)
            val color = when (st) { Budgets.State.OK -> teal(); Budgets.State.CLOSE -> amber(); Budgets.State.OVER -> MaterialTheme.colorScheme.error }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(Budgets.fraction(spent, limit).coerceAtLeast(0.01f)).height(8.dp).background(color, RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.height(6.dp))
            val perDay = Budgets.perDay(limit, spent, Budgets.daysLeftInMonth(System.currentTimeMillis()))
            Text(
                if (st == Budgets.State.OVER) "Over budget by ${Logic.money(spent - limit)}"
                else "${Logic.money(limit - spent)} left of ${Logic.money(limit)} · safe to spend ${Logic.money(perDay)} today",
                fontSize = 13.sp, color = if (st == Budgets.State.OVER) MaterialTheme.colorScheme.error else muted()
            )
        } else {
            Text("Set a monthly budget to see what is safe to spend each day ›", fontSize = 13.sp, color = muted())
        }
    }
}
