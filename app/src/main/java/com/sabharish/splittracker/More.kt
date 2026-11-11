package com.sabharish.splittracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun FullPage(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    FullScreenDialog(onDismiss) {
        Surface(Modifier.fillMaxSize().padding(LocalScreenInsets.current), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(4.dp)); content(); Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(emoji: String, title: String, sub: String, trailing: @Composable () -> Unit = { Text("›", fontSize = 22.sp, color = muted()) }, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.sp, color = muted()) }
            trailing()
        }
    }
}

/** Everything that is not a daily page, in one tidy list. */
@Composable
fun MoreDialog(vm: MainVm, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(false) }
    var recurring by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var lockOn by remember { mutableStateOf(AppLock.isOn(ctx)) }
    var note by remember { mutableStateOf("") }
    var autoDir by remember { mutableStateOf(AutoBackup.folder(ctx)) }
    var passOn by remember { mutableStateOf(Repo.getPref(ctx, "backupPass", "").isNotEmpty()) }
    var setPass by remember { mutableStateOf(false) }
    var monthStart by remember { mutableStateOf(Stats.startDay) }
    var askStart by remember { mutableStateOf(false) }
    var digestOn by remember { mutableStateOf(DigestReceiver.isOn(ctx)) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Repo.putPref(ctx, AutoBackup.DIR, uri.toString()); autoDir = uri.toString()
                vm.autoBackup(force = true); note = "Automatic backup is on. A copy is saved now and then every day you open the app."
            } catch (e: Exception) { CrashLog.record(ctx, e); note = "Couldn't use that folder" }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            note = try {
                val text = vm.csvText()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                "Saved. Open it in Excel or Google Sheets."
            } catch (e: Exception) { CrashLog.record(ctx, e); "Couldn't save the file" }
        }
    }
    FullPage("More", onDismiss) {
        MenuRow("🏦", "Accounts", "Balances of your banks, cards and cash") { accounts = true }
        MenuRow("🔁", "Repeating entries", "Rent, subscriptions, salary — added for you every month") { recurring = true }
        MenuRow("📤", "Export to spreadsheet", "A .csv file you save yourself") {
            save.launch("PaisaLog-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date())}.csv")
        }
        MenuRow("📅", "Month starts on day $monthStart", if (monthStart == 1) "Calendar months. Change it if your salary cycle runs, say, 25th to 24th" else "Months run from day $monthStart to day ${monthStart - 1}. The Calendar view stays on calendar months") { askStart = true }
        MenuRow("🔒", "App lock", if (AppLock.canUse(ctx)) "Ask for your phone's fingerprint, face or PIN" else "Set a screen lock on your phone first", trailing = {
            Switch(checked = lockOn, enabled = AppLock.canUse(ctx), onCheckedChange = { on -> lockOn = on; Repo.putPref(ctx, AppLock.KEY, if (on) "1" else "") })
        }) { if (AppLock.canUse(ctx)) { lockOn = !lockOn; Repo.putPref(ctx, AppLock.KEY, if (lockOn) "1" else "") } }
        MenuRow("💾", "Automatic backup", if (autoDir != null) "On · one file in your folder, replaced daily. Tap to turn off" else "Saves a backup file in a folder you choose, every day") {
            if (autoDir != null) { Repo.putPref(ctx, AutoBackup.DIR, ""); autoDir = null; note = "Automatic backup is off" } else pickFolder.launch(null)
        }
        MenuRow("🔑", "Backup passphrase", if (passOn) "On · backup files are encrypted. Keep it safe: it cannot be recovered" else "Protect backup files with a passphrase") { setPass = true }
        MenuRow("🌙", "Evening summary", "One quiet notification at 9 pm: what is left to review and today's spend", trailing = {
            Switch(checked = digestOn, onCheckedChange = { on -> digestOn = on; Repo.putPref(ctx, Digest.KEY, if (on) "1" else ""); DigestReceiver.schedule(ctx) })
        }) { digestOn = !digestOn; Repo.putPref(ctx, Digest.KEY, if (digestOn) "1" else ""); DigestReceiver.schedule(ctx) }
        MenuRow("⚙️", "Settings", "Tags, backup & restore, notifications, theme") { settings = true }
        if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = teal(), modifier = Modifier.padding(horizontal = 6.dp))
        Text("🔒 Nothing leaves this phone. This app has no internet permission.", fontSize = 12.sp, color = muted(), modifier = Modifier.padding(horizontal = 6.dp))
    }
    if (askStart) MonthStartDialog(monthStart, { askStart = false }) { d ->
        Repo.putPref(ctx, "monthStart", d.toString()); Stats.loadStartDay(d.toString()); monthStart = Stats.startDay; Epoch.value++; askStart = false
    }
    if (setPass) PassphraseSetDialog(passOn, { setPass = false }) { p ->
        Repo.putPref(ctx, "backupPass", p); passOn = p.isNotEmpty(); setPass = false
        if (autoDir != null) vm.autoBackup(force = true)
        note = if (p.isEmpty()) "Backups are no longer encrypted" else "Backups are now encrypted. Write the passphrase down somewhere safe."
    }
    if (accounts) AccountsDialog(vm) { accounts = false }
    if (recurring) RecurringDialog(vm) { recurring = false }
    if (settings) SettingsDialog(vm) { settings = false }
}

/** All accounts with their balances; tap one to correct it. */
@Composable
fun AccountsDialog(vm: MainVm, onDismiss: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }
    val known = accounts.filter { it.hasBalance }
    FullPage("Accounts", onDismiss) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp)) {
                Text("Total across ${known.size} ${if (known.size == 1) "account" else "accounts"}", fontSize = 13.sp, color = muted())
                Text(Logic.money(known.sumOf { it.balance }), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
        accounts.forEach { a ->
            Card(Modifier.fillMaxWidth().clickable { detail = a.key }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontSize = 16.sp)
                        val stale = a.hasBalance && System.currentTimeMillis() - a.asOf > 30L * 86_400_000L
                        Text(if (a.hasBalance) "Updated ${Repo.shortDate(a.asOf)}${if (stale) " · may be out of date" else ""} · tap for history" else "Tap to set balance and see history", fontSize = 12.sp, color = if (stale) amber() else muted())
                    }
                    if (a.hasBalance) Text(Logic.money(a.balance), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (a.balance < 0) MaterialTheme.colorScheme.error else green())
                }
            }
        }
        Text("Transfers between your own accounts are left out of your spends.", fontSize = 12.sp, color = muted(), modifier = Modifier.padding(horizontal = 6.dp))
    }
    detail?.let { k -> accounts.firstOrNull { it.key == k }?.let { a -> AccountDetail(vm, a) { detail = null } } }
}

/** Entries that repeat every month. They are created on the day, even if the app was closed — the next time it opens. */
@Composable
fun RecurringDialog(vm: MainVm, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val accounts = Accounts.options(vm.accounts.collectAsState().value)
    var items by remember { mutableStateOf(Recurrings.decode(Repo.getPref(ctx, Recurrings.KEY, ""))) }
    var adding by remember { mutableStateOf(false) }
    fun store(l: List<Recurring>) { items = l; Repo.putPref(ctx, Recurrings.KEY, Recurrings.encode(l)) }
    FullPage("Repeating entries", onDismiss) {
        if (items.isEmpty() && !adding) Text("Nothing repeating yet. Add rent, a subscription or your salary once and it is entered for you every month.", fontSize = 14.sp, color = muted(), modifier = Modifier.padding(6.dp))
        items.forEach { r ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${Entries.emoji(r.category)}  ${r.title.ifBlank { r.category }}", fontSize = 16.sp)
                        Text("${r.kind} · every month on the ${r.day}${if (r.day in 11..13) "th" else when (r.day % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }}", fontSize = 12.sp, color = muted())
                    }
                    Text(Logic.money(r.amount), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (r.kind == "Income") green() else MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { store(items.filter { it.id != r.id }) }) { Text("Stop", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (adding) {
            var kind by remember { mutableStateOf("Expense") }
            var title by remember { mutableStateOf("") }
            var amount by remember { mutableStateOf("") }
            var day by remember { mutableStateOf("1") }
            var category by remember { mutableStateOf("") }
            var account by remember { mutableStateOf(accounts.first { it.key == Accounts.CASH }) }
            val amt = Calc.eval(amount) ?: 0.0
            val d = day.toIntOrNull() ?: 0
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(listOf("Expense", "Income"), kind, { kind = it; category = "" })
                    OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Name (e.g. Rent)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, label = { Text("Amount ₹") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = day, onValueChange = { day = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("Day (1-28)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (kind == "Income") Entries.INCOME_CATS else Categories.all).forEach { c -> Pill("${Entries.emoji(c)} $c", { category = c }, selected = c == category, compact = true) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { a -> Pill(a.label, { account = a }, selected = a.key == account.key, compact = true) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { adding = false }) { Text("Cancel") }
                        Button(enabled = amt > 0 && d in 1..28, onClick = {
                            val now = System.currentTimeMillis()
                            store(items + Recurring(now.toString(), title.trim(), amt, kind, category.ifBlank { "Other" }, account.key, d, Recurrings.startLast(d, now)))
                            vm.runRecurring(); adding = false
                        }) { Text("Save") }
                    }
                }
            }
        } else Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add a repeating entry") }
    }
}

/** Asks for the passphrase of an encrypted backup, then hands back the plain text. */
@Composable
fun PassphraseAsk(encrypted: String, onPlain: (String) -> Unit, onCancel: () -> Unit) {
    var p by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This backup is protected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the passphrase you set when you saved it.", fontSize = 14.sp)
                OutlinedTextField(value = p, onValueChange = { p = it; bad = false }, singleLine = true, label = { Text("Passphrase") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (bad) Text("That passphrase doesn't open this backup.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = p.isNotEmpty() && !busy, onClick = {
                busy = true
                scope.launch {
                    val plain = withContext(Dispatchers.Default) { Crypt.decrypt(encrypted, p) }
                    busy = false
                    if (plain == null) bad = true else onPlain(plain)
                }
            }) { Text(if (busy) "Checking…" else "Open") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun PassphraseSetDialog(isOn: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 6 && a == b
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup files (including the automatic one) are encrypted with it. If you lose the passphrase, those backups cannot be opened by anyone, including me.", fontSize = 13.sp, color = muted())
                OutlinedTextField(value = a, onValueChange = { a = it }, singleLine = true, label = { Text("New passphrase (6+ characters)") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(value = b, onValueChange = { b = it }, singleLine = true, label = { Text("Type it again") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (a.isNotEmpty() && b.isNotEmpty() && a != b) Text("The two don't match.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                if (isOn) TextButton(onClick = { onSave("") }) { Text("Turn off encryption", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(a) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The receipt photo for one transaction: view, replace or remove. Stays on this phone and is not part of backups. */
@Composable
fun ReceiptDialog(txnId: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val bmp = remember(version) { Receipts.load(ctx, txnId) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { Receipts.save(ctx, txnId, uri); version++ } }
    FullPage("Receipt", onDismiss) {
        if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), contentDescription = "Receipt photo", modifier = Modifier.fillMaxWidth())
        else Text("No photo yet.", fontSize = 14.sp, color = muted())
        Button(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text(if (bmp != null) "Replace photo" else "Choose a photo") }
        if (bmp != null) TextButton(onClick = { Receipts.delete(ctx, txnId); version++ }) { Text("Remove photo", color = MaterialTheme.colorScheme.error) }
        Text("Kept only on this phone. Photos are not included in backups.", fontSize = 12.sp, color = muted())
    }
}

/** Shown once to a brand-new person: what is read, what stays private, how to add. */
@Composable
fun TourDialog(onDone: () -> Unit) {
    val steps = listOf(
        Triple("📩", "Payments arrive by themselves", "Bank and UPI messages are read on this phone and show up in your Inbox. Tag each one: yours, or split with someone."),
        Triple("🔒", "Nothing leaves your phone", "This app has no internet access at all. Your data stays here. Save a backup file now and then (More ▸ Automatic backup does it for you)."),
        Triple("➕", "Cash or something missed?", "Tap + to add it in a few seconds. You can type 120+45 in the amount, and save things you add often as quick entries.")
    )
    var i by remember { mutableStateOf(0) }
    val (emoji, title, body) = steps[i]
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("$emoji  $title") },
        text = { Text(body, fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = { if (i < steps.lastIndex) i++ else onDone() }) { Text(if (i < steps.lastIndex) "Next" else "Got it") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Skip") } }
    )
}

@Composable
private fun MonthStartDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val d = text.toIntOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Month starts on day") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick 1 to use calendar months, or a day from 2 to 28 (for example 25 if your salary arrives then). Totals, budgets and the Transactions month follow it.", fontSize = 13.sp, color = muted())
                OutlinedTextField(value = text, onValueChange = { text = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("Day (1-28)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(enabled = d != null && d in 1..28, onClick = { onSave(d!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One account: its balance, how that balance has moved, and ways to correct or check it. */
@Composable
private fun AccountDetail(vm: MainVm, a: AccountView, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val cur = accounts.firstOrNull { it.key == a.key } ?: a
    val history = remember(cur.asOf, cur.balance) { Repo.balanceHistory(ctx, a.key) }
    var edit by remember { mutableStateOf(false) }
    var statement by remember { mutableStateOf(false) }
    FullPage(cur.label, onDismiss) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp)) {
                Text("Balance now", fontSize = 13.sp, color = muted())
                Text(if (cur.hasBalance) Logic.money(cur.balance) else "Not set", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                if (cur.hasBalance) Text("From ${Repo.shortDate(cur.asOf)}, plus payments since", fontSize = 12.sp, color = muted())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { edit = true }) { Text(if (cur.hasBalance) "Correct balance" else "Set balance") }
            androidx.compose.material3.OutlinedButton(onClick = { statement = true }) { Text("Check a statement") }
        }
        if (history.size >= 2) {
            val points = history.take(12).reversed()
            val top = points.maxOf { Math.abs(it.amount) }.coerceAtLeast(1.0)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("How it moved", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        points.forEachIndexed { i, p ->
                            Box(Modifier.weight(1f).height((60 * (Math.abs(p.amount) / top)).toFloat().coerceAtLeast(3f).dp)
                                .background(if (i == points.lastIndex) teal() else teal().copy(alpha = 0.4f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                    }
                }
            }
        }
        if (history.isEmpty()) Text("No history yet. Each time you set the balance, or a bank message reports it, it is noted here.", fontSize = 13.sp, color = muted(), modifier = Modifier.padding(6.dp))
        history.take(10).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(Repo.shortDate(p.at), fontSize = 14.sp)
                    Text(when (p.source) { "message" -> "From a bank message"; "statement" -> "From a statement"; else -> "Set by you" }, fontSize = 12.sp, color = muted())
                }
                Text(Logic.money(p.amount), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (edit) BalanceDialog(cur, { edit = false }) { n, b -> vm.setBalance(a.key, n, b); edit = false }
    if (statement) StatementFlow(vm, initial = "acct|${a.key}") { statement = false }
}
