package com.sabharish.splittracker

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ---------------------------------------------------------------
// Full-screen "who was this for?" sheet
// ---------------------------------------------------------------
@Composable
fun TagSheet(vm: MainVm, t: Txn, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val contacts by vm.contacts.collectAsState()
    val recents by vm.recents.collectAsState()
    val sug by produceState<Rule?>(null, t.id) { value = vm.suggestion(t) }

    var category by remember { mutableStateOf(t.category) }
    val picked = remember { mutableStateListOf<String>() }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var splitMode by remember { mutableStateOf("EQUAL") }   // EQUAL | PERCENT | PARTS | AMOUNT
    val vals = remember { mutableStateMapOf<String, String>() }
    val aliases by vm.aliases.collectAsState()
    var rename by remember { mutableStateOf(false) }
    var alreadyPaid by remember { mutableStateOf(false) }
    var splitAdded by remember { mutableStateOf(false) }
    var rememberRule by remember { mutableStateOf(true) }

    fun equal(): List<NewShare> = Repo.equalShares(t.amount, picked.toList(), alreadyPaid)

    fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    /** Fill sensible starting values whenever the people or the split mode change. */
    fun refill() {
        vals.clear()
        val names = picked.toList()
        if (names.size < 2) return
        when (splitMode) {
            "PERCENT" -> {
                val base = Math.floor(10000.0 / names.size) / 100
                names.forEachIndexed { i, n -> vals[n] = fmt(if (i == names.size - 1) 100.0 - base * (names.size - 1) else base) }
            }
            "PARTS" -> names.forEach { vals[it] = "1" }
            "AMOUNT" -> equal().forEach { vals[it.person] = fmt(it.amount) }
        }
    }

    fun add(name: String) { if (!picked.contains(name)) picked.add(name); refill() }
    fun toggle(name: String) { if (picked.contains(name)) picked.remove(name) else picked.add(name); refill() }

    fun build(): List<NewShare>? {
        if (picked.isEmpty()) return null
        val names = picked.toList()
        if (names.size < 2 || splitMode == "EQUAL") return equal()
        val nums = ArrayList<Double>()
        for (n in names) nums.add(vals[n]?.toDoubleOrNull() ?: return null)
        return when (splitMode) {
            "PERCENT" -> if (Math.abs(nums.sum() - 100.0) > 0.01) null else Repo.weightedShares(t.amount, names, nums, alreadyPaid)
            "PARTS" -> Repo.weightedShares(t.amount, names, nums, alreadyPaid)
            else -> if (Math.abs(nums.sum() - t.amount) > 0.011) null
                    else names.mapIndexed { i, n -> NewShare(n, nums[i], n == Repo.ME || alreadyPaid) }
        }
    }

    val shares = build()
    val owedToYou = (shares ?: emptyList()).filter { it.person != Repo.ME && !it.paid }.sumOf { it.amount }
    val othersInvolved = picked.any { it != Repo.ME }

    val peopleChips = (listOf(Repo.ME) + picked.filter { it != Repo.ME && it !in recents } + recents).distinct()
    val q = query.trim()
    val results = contacts.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(8)

    if (rename) AliasDialog(t.merchant, aliases[Repo.aliasKey(t.merchant).removePrefix("alias|")] ?: "", { rename = false }) { vm.setAlias(t.merchant, it); rename = false }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Repo.money(t.amount), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(Repo.title(t.merchant, aliases), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${Repo.acct(t.bank, t.card)} · ${Repo.shortDate(t.txnDate)}", fontSize = 13.sp, color = muted())
                        TextButton(onClick = { rename = true }) { Text("Rename merchant") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(14.dp))
                    Text("Tag", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Categories.all.forEach { c -> Pill(c, { category = if (category == c) "" else c }, selected = category == c) }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("Who was this for?", fontWeight = FontWeight.SemiBold)
                    Text("Pick one person if it was all theirs. Pick several to split equally.", fontSize = 12.sp, color = muted())
                    Spacer(Modifier.height(6.dp))
                    val s = sug
                    if (s != null && s.action == "PEOPLE" && s.people.isNotBlank()) {
                        val names = s.people.split(",").filter { it.isNotBlank() }
                        Pill("As before: " + names.joinToString(", ") { if (it == Repo.ME) "You" else it }, {
                            picked.clear(); picked.addAll(names); refill()
                        }, strong = true)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        peopleChips.forEach { p -> Pill(if (p == Repo.ME) "Me" else p, { toggle(p) }, selected = picked.contains(p)) }
                        Pill(if (showSearch) "Hide contacts" else "+ Contacts", { showSearch = !showSearch })
                    }
                    if (showSearch) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text(if (contacts.isEmpty()) "Type a name" else "Search contacts or type a name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (q.isNotEmpty() && contacts.none { it.equals(q, true) }) {
                            Text("Add \"$q\"", color = teal(), fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().clickable { add(q); query = "" }.padding(vertical = 10.dp))
                        }
                        results.forEach { name ->
                            Text(name, modifier = Modifier.fillMaxWidth().clickable {
                                add(name); query = ""
                            }.padding(vertical = 10.dp))
                        }
                    }

                    if (picked.isNotEmpty()) {
                        if (picked.size >= 2) {
                            Spacer(Modifier.height(16.dp))
                            Text("How to split", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("EQUAL" to "Equal", "PERCENT" to "Percent", "PARTS" to "Parts", "AMOUNT" to "Amounts").forEach { (m, label) ->
                                    Pill(label, { splitMode = m; refill() }, selected = splitMode == m)
                                }
                            }
                            if (splitMode == "PARTS") Text("Example: 2 and 1 means one person pays twice the other.", fontSize = 12.sp, color = muted())
                        }
                        Spacer(Modifier.height(8.dp))
                        if (picked.size >= 2 && splitMode != "EQUAL") {
                            val unit = when (splitMode) { "PERCENT" -> "%"; "PARTS" -> "parts"; else -> "₹" }
                            picked.forEach { p ->
                                OutlinedTextField(
                                    value = vals[p] ?: "", onValueChange = { vals[p] = it }, singleLine = true,
                                    label = { Text((if (p == Repo.ME) "You" else p) + " ($unit)") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                )
                            }
                            if (shares == null) Text(
                                when (splitMode) { "PERCENT" -> "Percentages must add up to 100"; "PARTS" -> "Enter a number for each person"; else -> "Amounts must add up to ${Repo.money(t.amount)}" },
                                color = MaterialTheme.colorScheme.error, fontSize = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        (shares ?: emptyList()).forEach { sh ->
                            Text("${if (sh.person == Repo.ME) "You" else sh.person}: ${Repo.money(sh.amount)}", fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                !othersInvolved -> "Counts as your own spend."
                                owedToYou > 0 -> "${Repo.money(owedToYou)} will show as owed to you."
                                else -> "Nothing is owed to you."
                            },
                            fontWeight = FontWeight.SemiBold, color = teal()
                        )
                    }

                    if (othersInvolved) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = alreadyPaid, onCheckedChange = { alreadyPaid = it }); Text("They already paid me")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = splitAdded, onCheckedChange = { splitAdded = it }); Text("Added to PhonePe Split")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberRule, onCheckedChange = { rememberRule = it })
                        Text("Remember for ${t.merchant}")
                    }
                }
                Button(
                    enabled = shares != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        val sh = build() ?: return@Button
                        vm.tag(t, sh, category, rememberRule, splitAdded && othersInvolved)
                        onSaved(if (!othersInvolved) "Tagged as yours" else "Saved")
                        onDismiss()
                    }
                ) { Text("Save", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun AliasDialog(raw: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename merchant") },
        text = {
            Column {
                Text("Bank shows: $raw", fontSize = 13.sp, color = muted())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Show as (for example CRED)") })
                Spacer(Modifier.height(6.dp))
                Text("Both names are shown, so you can still recognise it. Leave empty to remove the name.", fontSize = 12.sp, color = muted())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------
// Link a refund / reversal to the purchase it belongs to
// ---------------------------------------------------------------
@Composable
fun LinkRefundDialog(vm: MainVm, refund: Txn, onDismiss: () -> Unit) {
    val spends by produceState(emptyList<Txn>(), refund.id) { value = vm.recentSpends(refund.amount) }
    val aliases by vm.aliases.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which purchase was refunded?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${Repo.money(refund.amount)} came back. Closest matches first. Anything others owed for it shrinks automatically.", fontSize = 12.sp, color = muted())
                Spacer(Modifier.height(8.dp))
                if (spends.isEmpty()) Text("No purchases found.")
                spends.forEach { s ->
                    Column(Modifier.fillMaxWidth().clickable { vm.linkRefund(refund.id, s.id); onDismiss() }.padding(vertical = 8.dp)) {
                        Text("${Repo.title(s.merchant, aliases)} · ${Repo.money(s.amount)}", fontWeight = FontWeight.SemiBold)
                        Text("${Repo.acct(s.bank, s.card)} · ${fullDate(s.txnDate)}", fontSize = 12.sp, color = muted())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------
// Pick one person (contacts, recents, or a typed name)
// ---------------------------------------------------------------
@Composable
fun PersonPickerDialog(vm: MainVm, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val contacts by vm.contacts.collectAsState()
    val recents by vm.recents.collectAsState()
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val pool = (recents + contacts).distinct()
    val results = pool.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(10)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search or type a name") }, modifier = Modifier.fillMaxWidth())
                if (q.isNotEmpty() && pool.none { it.equals(q, true) }) {
                    Text("Add \"$q\"", color = teal(), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(q) }.padding(vertical = 10.dp))
                }
                results.forEach { n -> Text(n, modifier = Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 10.dp)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------
// Mark paid (manual settlement)
// ---------------------------------------------------------------
@Composable
fun SettleDialog(person: String, owed: Double, onDismiss: () -> Unit, onSave: (Double, String) -> Unit) {
    var amt by remember { mutableStateOf(String.format(java.util.Locale.US, "%.2f", owed)) }
    var mode by remember { mutableStateOf("UPI") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$person paid you") },
        text = {
            Column {
                Text("Clears the oldest items first. Partial payments are fine.", fontSize = 12.sp, color = muted())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = amt, onValueChange = { amt = it }, singleLine = true, label = { Text("Amount received") })
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("UPI", { mode = "UPI" }, selected = mode == "UPI")
                    Pill("Cash", { mode = "Cash" }, selected = mode == "Cash")
                    Pill("Other", { mode = "Other" }, selected = mode == "Other")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { val v = amt.toDoubleOrNull(); if (v != null && v > 0) onSave(v, mode) else onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------
// Reminder preview: edit, copy or share
// ---------------------------------------------------------------
@Composable
fun ReminderDialog(initial: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder message") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(260.dp))
        },
        confirmButton = {
            Button(onClick = { share(ctx, text); onDismiss() }) { Text("Send…") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { clip.setText(AnnotatedString(text)); onDismiss() }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun share(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(i, "Send reminder").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------
// Settings
// ---------------------------------------------------------------
@Composable
fun SettingsDialog(vm: MainVm, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var upi by remember { mutableStateOf(Repo.getPref(ctx, "upi", "")) }
    var names by remember { mutableStateOf(Repo.getPref(ctx, "selfnames", "sabharish")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = upi, onValueChange = { upi = it }, singleLine = true,
                    label = { Text("My UPI ID (added to reminders)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = names, onValueChange = { names = it }, singleLine = true,
                    label = { Text("My name words (to hide own transfers)") }, modifier = Modifier.fillMaxWidth())
                Text("Comma separated. UPI payments to or from a name containing any of these are treated as your own transfers.", fontSize = 12.sp, color = muted())
                Spacer(Modifier.height(14.dp))
                Text("Appearance", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (m, label) ->
                        Pill(label, { ThemeState.mode = m; Repo.putPref(ctx, "theme", m) }, selected = ThemeState.mode == m)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Import older messages", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.importHistory(180); onDismiss() }) { Text("6 months") }
                    OutlinedButton(onClick = { vm.importHistory(365); onDismiss() }) { Text("1 year") }
                    OutlinedButton(onClick = { vm.importHistory(1095); onDismiss() }) { Text("3 years") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Repo.putPref(ctx, "upi", upi.trim())
                Repo.putPref(ctx, "selfnames", names.trim())
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
