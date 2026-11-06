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
    var checking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountView?>(null) }
    val total = owed.values.sum()
    val toReview = spends.size + money.size
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (owed.isEmpty()) "All settled 🎉" else "${owed.size} ${if (owed.size == 1) "person" else "people"} · tap to see details",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp
                )
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
                        Text(Habit.clarityLine(((clarity ?: 1f) * 100).toInt(), spends.size), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (days > 0) "🔥 $days-day inbox-zero streak" else "Clear your inbox today to start a streak", fontSize = 13.sp, color = if (days > 0) amber() else muted())
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("To review", toReview.toString(), if (toReview > 0) amber() else green(), Modifier.weight(1f)) { go(1) }
                StatTile("Awaiting refund", failed.size.toString(), if (failed.isNotEmpty()) amber() else green(), Modifier.weight(1f)) { go(1) }
            }
        }
        if (accounts.isNotEmpty()) {
            item { Text("My accounts", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
            accounts.forEach { a ->
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { editing = a }, shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.label, fontSize = 16.sp)
                                if (!a.hasBalance) Text("Tap to enter today's balance", fontSize = 12.sp, color = muted())
                            }
                            if (a.hasBalance) Text(Logic.money(a.balance), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                color = if (a.balance < 0) MaterialTheme.colorScheme.error else green())
                        }
                    }
                }
            }
            item { Text("Moves between your own accounts are spotted automatically and kept out of your spends.", fontSize = 12.sp, color = muted()) }
        }
        item {
            Card(
                Modifier.fillMaxWidth().clickable { checking = true }, shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Check a statement", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Upload a card or bank statement and see what's missing or extra", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
    editing?.let { a -> BalanceDialog(a, { editing = null }) { n, b -> vm.setBalance(a.key, n, b); editing = null } }
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
private fun BalanceDialog(a: AccountView, onDismiss: () -> Unit, onSave: (String, Double) -> Unit) {
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
                Text("From now on every UPI in/out message updates it.", fontSize = 12.sp, color = muted())
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
private fun StatementFlow(vm: MainVm, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val all by vm.all.collectAsState()
    val sources = remember(all.size) { Statement.sources(all) }
    var source by remember { mutableStateOf<StmtSource?>(null) }
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
                else { result = Statement.reconcile(lines, Statement.txnsFor(s, all)); message = "" }
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
                    if (sources.isEmpty()) Text("Nothing to check yet. Statements can be checked once some transactions are in.", fontSize = 13.sp, color = muted())
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
