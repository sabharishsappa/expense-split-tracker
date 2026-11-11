package com.sabharish.splittracker

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ---------------------------------------------------------------
// Full-screen "who was this for?" sheet
// ---------------------------------------------------------------
@Composable
fun TagSheet(
    vm: MainVm, t: Txn, onDismiss: () -> Unit, onSaved: (String) -> Unit, initial: List<String> = emptyList(),
    /** Creating a brand-new split: title and amount are typed here, and nothing is stored until ✓. */
    creating: Boolean = false,
    groups: List<Groups.Group> = emptyList(),
    onCreated: (Txn, List<String>) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var titleText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    val amt = if (creating) (amountText.replace(",", "").toDoubleOrNull() ?: 0.0) else t.amount
    val contacts by vm.contacts.collectAsState()
    val recents by vm.recents.collectAsState()
    val sug by produceState<Rule?>(null, t.id) { value = vm.suggestion(t) }

    var category by remember { mutableStateOf(t.category) }
    val picked = remember { mutableStateListOf<String>().also { it.addAll(initial) } }
    var query by remember { mutableStateOf("") }
    var splitMode by remember { mutableStateOf("EQUAL") }   // EQUAL | PERCENT | PARTS | AMOUNT
    val vals = remember { mutableStateMapOf<String, String>() }
    val aliases by vm.aliases.collectAsState()
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var refundFor by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf(false) }
    var alreadyPaid by remember { mutableStateOf(false) }
    var splitAdded by remember { mutableStateOf(false) }
    var rememberRule by remember { mutableStateOf(!creating) }

    fun equal(): List<NewShare> = Repo.equalShares(amt, picked.toList(), alreadyPaid)

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
            "PERCENT" -> if (Math.abs(nums.sum() - 100.0) > 0.01) null else Repo.weightedShares(amt, names, nums, alreadyPaid)
            "PARTS" -> Repo.weightedShares(amt, names, nums, alreadyPaid)
            else -> if (Math.abs(nums.sum() - amt) > 0.011) null
                    else names.mapIndexed { i, n -> NewShare(n, nums[i], n == Repo.ME || alreadyPaid) }
        }
    }

    // Editing a saved split: start from what was saved
    val existing by produceState<List<Share>>(emptyList(), t.id) { value = vm.sharesFor(t.id) }
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(existing) {
        if (!loaded && existing.isNotEmpty()) {
            loaded = true
            Logic.splitFromExisting(amt, existing)?.let { ex ->
                picked.clear(); picked.addAll(ex.people)
                alreadyPaid = ex.allPaid
                splitMode = ex.mode
                splitAdded = t.splitAdded
                rememberRule = false
                refill()
                if (ex.mode == "AMOUNT") ex.people.forEach { vals[it] = fmt(ex.amounts[it] ?: 0.0) }
            }
        }
    }

    val shares = build()
    val owedToYou = (shares ?: emptyList()).filter { it.person != Repo.ME && !it.paid }.sumOf { it.amount }
    val othersInvolved = picked.any { it != Repo.ME }

    val peopleChips = (listOf(Repo.ME) + picked.filter { it != Repo.ME && it !in recents } + recents.take(5)).distinct()
    val q = query.trim()
    val results = contacts.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(8)

    if (rename) AliasDialog(t.merchant, aliases[Repo.aliasKey(t.merchant).removePrefix("alias|")] ?: "", { rename = false }) { vm.setAlias(t.merchant, it); rename = false }

    FullScreenDialog(onDismiss) {
        Surface(Modifier.fillMaxSize().padding(LocalScreenInsets.current), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    if (creating) {
                        Text("New split", Modifier.weight(1f).padding(horizontal = 6.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    } else Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(Repo.money(t.amount), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(Repo.title(t.merchant, aliases), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${Repo.acct(t.bank, t.card)} · ${Repo.shortDate(t.txnDate)}", fontSize = 12.sp, color = muted())
                    }
                    FilledIconButton(
                        enabled = shares != null && amt > 0.004, modifier = Modifier.size(52.dp),
                        onClick = {
                            val sh = build() ?: return@FilledIconButton
                            if (creating) {
                                val people = sh.map { it.person }
                                val title = titleText
                                scope.launch {
                                    try {
                                        val made = Repo.addManual(ctx, title, amt)
                                        Repo.tag(ctx, made, sh, category, false, splitAdded && othersInvolved)
                                        onCreated(made, people)
                                    } catch (e: Exception) {
                                        CrashLog.record(ctx, e); vm.status.value = "Couldn't save the split. Details are in Settings."
                                    }
                                }
                            } else {
                                vm.tag(t, sh, category, rememberRule, splitAdded && othersInvolved)
                                onSaved(if (!othersInvolved) "Tagged as yours" else "Saved")
                                onDismiss()
                            }
                        }
                    ) { Icon(Icons.Default.Check, contentDescription = "Save") }
                }
                if (!creating) Row {
                    if (Entries.isHand(t)) TextButton(onClick = { editEntry = true }) { Text("Edit") }
                    TextButton(onClick = { rename = true }) { Text("Rename merchant") }
                    if (t.kind == "SPEND" || t.kind == "UPI_OUT") TextButton(onClick = { refundFor = true }) { Text("Refund received") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                if (editEntry) AddEntryDialog(vm, edit = t) { editEntry = false; onDismiss() }
                if (refundFor) RefundDialog(t, { refundFor = false }) { a -> refundFor = false; vm.refunded(t, a); onDismiss() }
                if (confirmDelete) AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete this transaction?") },
                    text = { Text("${t.merchant} · ${Repo.money(t.amount)}\nIt is removed together with its split. Use this for duplicates or entries you don't want. It won't come back when messages are read again.") },
                    confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(t); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (creating) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = titleText, onValueChange = { titleText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("What for?") }, placeholder = { Text("Badminton, Dinner, Trip…") })
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = amountText, onValueChange = { amountText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Total amount ₹") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                        if (groups.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                groups.forEach { g ->
                                    Pill(g.name, { titleText = g.name; picked.clear(); picked.add(Repo.ME); picked.addAll(g.people); refill() },
                                        selected = titleText == g.name, compact = true)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Tag", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val tagScroll = rememberScrollState()
                    // after a pick the chosen tag moves to the front, so bring the row back to the front too
                    androidx.compose.runtime.LaunchedEffect(category) { tagScroll.animateScrollTo(0) }
                    Row(Modifier.horizontalScroll(tagScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tagList by vm.tags.collectAsState()
                        var newTag by remember { mutableStateOf(false) }
                        Logic.selectedFirst(tagList, listOf(category).filter { it.isNotBlank() }).forEach { c -> Pill(c, { category = if (category == c) "" else c }, selected = category == c) }
                        Pill("+ New", { newTag = true })
                        if (newTag) NewTagDialog({ newTag = false }) { n -> vm.addTag(n)?.let { category = it }; newTag = false }
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
                        }, selected = picked.toList() == names, strong = picked.toList() == names)
                        Spacer(Modifier.height(8.dp))
                    }
                    val peopleScroll = rememberScrollState()
                    androidx.compose.runtime.LaunchedEffect(picked.toList()) { peopleScroll.animateScrollTo(0) }
                    Row(Modifier.horizontalScroll(peopleScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Logic.selectedFirst(peopleChips, picked.toList()).forEach { p -> Pill(if (p == Repo.ME) "Me" else p, { toggle(p) }, selected = picked.contains(p)) }
                    }
                    run {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("Type a name or search contacts") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (q.isNotEmpty() && contacts.none { it.equals(q, true) }) {
                            Text("Add \"$q\"", color = teal(), fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().clickable { add(q); query = "" }.padding(vertical = 10.dp))
                        }
                        if (q.isNotEmpty()) results.forEach { name ->
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
                                when (splitMode) { "PERCENT" -> "Percentages must add up to 100"; "PARTS" -> "Enter a number for each person"; else -> "Amounts must add up to ${Repo.money(amt)}" },
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
                    if (!creating) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberRule, onCheckedChange = { rememberRule = it })
                        Text("Remember for ${t.merchant}")
                    }
                }
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
                SearchBox(query, { query = it }, "Search or type a name")
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
fun ReminderDialog(person: String, build: (String) -> String, onDismiss: () -> Unit, onSent: () -> Unit = {}) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var upi by remember { mutableStateOf(Repo.getPref(ctx, "upi", "")) }
    var text by remember { mutableStateOf(build(upi)) }
    var number by remember { mutableStateOf("") }
    var changing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(person) { number = Repo.phoneFor(ctx, person) }
    val wa = Logic.waNumber(number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind $person") },
        text = {
            Column {
                if (!changing && wa != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("WhatsApp: +$wa", Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = { changing = true }) { Text("Change") }
                    }
                } else {
                    OutlinedTextField(value = number, onValueChange = { number = it }, singleLine = true,
                        label = { Text(if (wa == null && number.isEmpty()) "No number found. Enter WhatsApp number" else "WhatsApp number") },
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = upi, onValueChange = { upi = it; text = build(it.trim()) }, singleLine = true,
                    label = { Text("Your UPI ID (added to message)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(200.dp))
            }
        },
        confirmButton = {
            Button(enabled = wa != null, onClick = {
                Repo.putPref(ctx, "upi", upi.trim()); Repo.savePhone(ctx, person, number)
                sendWhatsApp(ctx, wa!!, text); onSent(); onDismiss()
            }) { Text("WhatsApp") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { Repo.putPref(ctx, "upi", upi.trim()); clip.setText(AnnotatedString(text)); onSent(); onDismiss() }) { Text("Copy") }
                TextButton(onClick = { Repo.putPref(ctx, "upi", upi.trim()); share(ctx, text); onSent(); onDismiss() }) { Text("Other app") }
            }
        }
    )
}

/** Opens the chat with that number, message filled in. Falls back to the browser link if WhatsApp isn't installed. */
private fun sendWhatsApp(ctx: Context, number: String, text: String) {
    val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
        try {
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.setPackage(pkg)
            ctx.startActivity(i)
            return
        } catch (_: Exception) {
        }
    }
}

private fun share(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(i, "Send reminder").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------
// Settings
// ---------------------------------------------------------------
/** A titled group of settings on a rounded card. */
@Composable
private fun Section(title: String, hint: String = "", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = muted(), modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
            if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = muted())
        }
    }
}

/** Full-screen settings. Everything saves as you change it; there is no Save button. */
@Composable
fun SettingsDialog(vm: MainVm, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var upi by remember { mutableStateOf(Repo.getPref(ctx, "upi", "")) }
    val scope = rememberCoroutineScope()
    val last by vm.lastBackup.collectAsState()
    var note by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            note = try {
                val text = vm.backupText()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                "Backup saved · " + (Backup.describe(text) ?: "")
            } catch (e: Exception) { CrashLog.record(ctx, e); "Couldn't save the backup file" }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                confirmRestore = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: "" }
            } catch (e: Exception) { CrashLog.record(ctx, e); note = "Couldn't read that file" }
        }
    }
    confirmRestore?.let { text ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                val info = remember(text) { Backup.describe(text) }
                val at = remember(text) { Backup.savedAt(text) }
                Text(
                    if (info == null) "This file is not a Paisa Log backup."
                    else "Saved ${if (at > 0) Repo.shortDate(at) else "earlier"}\n$info\n\nEverything on this phone is replaced by it. If this is not what you expect, cancel and pick a newer file."
                )
            },
            confirmButton = { TextButton(enabled = remember(text) { Backup.describe(text) != null }, onClick = { vm.restore(text); confirmRestore = null; onDismiss() }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } }
        )
    }
    FullScreenDialog(onDismiss) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize().padding(LocalScreenInsets.current), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Section("Privacy", "This app has no internet permission. Your messages, people and amounts stay on this phone. They only leave if you choose to: a backup file you save, or a reminder you send yourself.") {
                        Text("🔒 Nothing is uploaded or published", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Section("Payments", "Your UPI ID is put in the reminder message so people know where to pay.") {
                        OutlinedTextField(value = upi, onValueChange = { upi = it; Repo.putPref(ctx, "upi", it.trim()) }, singleLine = true,
                            label = { Text("My UPI ID") }, modifier = Modifier.fillMaxWidth())
                    }
                    Section("Tags", "Your own tags come first — tap to rename, hide or delete. Built-in tags can only be hidden.") {
                        val mine by vm.customTags.collectAsState()
                        val hidden by vm.hiddenTags.collectAsState()
                        var newTag by remember { mutableStateOf(false) }
                        var editing by remember { mutableStateOf<String?>(null) }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (mine + (vm.tags.collectAsState().value.filter { it != "Other" } + hidden).distinct().filter { it !in mine }).forEach { c ->
                                Pill(if (c in hidden) "$c (hidden)" else c, { editing = c }, compact = true)
                            }
                            Pill("+ New", { newTag = true }, compact = true)
                        }
                        if (newTag) NewTagDialog({ newTag = false }) { n -> vm.addTag(n); newTag = false }
                        editing?.let { t -> EditTagDialog(vm, t, t in mine, t in hidden) { editing = null } }
                    }
                    Section("Appearance") {
                        Segmented(listOf("Auto", "Light", "Dark"),
                            when (ThemeState.mode) { "light" -> "Light"; "dark" -> "Dark"; else -> "Auto" },
                            { label -> val m = when (label) { "Light" -> "light"; "Dark" -> "dark"; else -> "system" }; ThemeState.mode = m; Repo.putPref(ctx, "theme", m) })
                    }
                    Section("Backup & restore", "Saves transactions, bills, tags, people, rules and settings to one file. Restore replaces what is on this phone.") {
                        Text(if (last > 0) "Last backup: ${Repo.shortDate(last)}" else "No backup yet", fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saveFile.launch("PaisaLog-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date())}.json") }) { Text("Save backup") }
                            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Restore") }
                        }
                        if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = teal())
                    }
                    Section("Import older messages", "Safe to repeat — messages already in the app are skipped.") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("1 month" to 30, "3 months" to 90, "6 months" to 180, "1 year" to 365).forEach { (label, d) ->
                                OutlinedButton(onClick = { vm.importDays(d); onDismiss() }) { Text(label) }
                            }
                        }
                    }
                    val rules by vm.tagRules.collectAsState()
                    var showRules by remember { mutableStateOf(false) }
                    Section("Remembered merchants", "A merchant you tagged once is tagged automatically next time.") {
                        Row(Modifier.fillMaxWidth().clickable { showRules = !showRules }, verticalAlignment = Alignment.CenterVertically) {
                            Text("${rules.size} remembered", Modifier.weight(1f), fontSize = 15.sp)
                            Text(if (showRules) "Hide" else "Show", color = teal())
                        }
                        if (showRules) {
                            rules.take(40).forEach { r ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${r.ruleKey.removePrefix("cat|")} → ${r.people}", Modifier.weight(1f), fontSize = 13.sp)
                                    TextButton(onClick = { vm.removeTagRule(r.ruleKey) }) { Text("Forget") }
                                }
                            }
                            if (rules.size > 40) Text("…and ${rules.size - 40} more", fontSize = 12.sp, color = muted())
                        }
                    }
                    val listenerOn = remember(Perm.tick) { Repo.notificationAccessOn(ctx) }
                    Section("Read notifications too", "Also notes payments shown in notifications from Google Pay, PhonePe, Paytm, your bank apps, your messages app and mail apps like Gmail. A payment that arrives by text and by notification is counted once. Mail is read only through its notification preview, so the amount must appear there. Nothing leaves this phone.") {
                        Text(if (listenerOn) "✓ On" else "Off", fontWeight = FontWeight.SemiBold, color = if (listenerOn) green() else muted())
                        OutlinedButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                            Text(if (listenerOn) "Manage notification access" else "Allow notification access")
                        }
                        if (!listenerOn) Text("If Android says the setting is restricted, open Settings > Apps > Paisa Log > ⋮ > Allow restricted settings, then try again.", fontSize = 12.sp, color = muted())
                    }
                    var check by remember { mutableStateOf<Diagnostics.ReadCheck?>(null) }
                    var checking by remember { mutableStateOf(false) }
                    Section("Message reading check", "Finds out why some bank messages may not show up. Nothing is saved or sent anywhere.") {
                        OutlinedButton(enabled = !checking, onClick = { checking = true; scope.launch { check = Repo.readCheck(ctx); checking = false } }) {
                            Text(if (checking) "Checking…" else "Run check")
                        }
                        check?.let { r -> Diagnostics.verdict(r).forEach { Text("• $it", fontSize = 14.sp) } }
                    }
                    var unparsed by remember { mutableStateOf(Repo.unparsed(ctx)) }
                    var showUnparsed by remember { mutableStateOf(false) }
                    if (unparsed.isNotEmpty()) Section("Messages I couldn't read", "Bank-like messages that matched nothing. Copy one and send it to the developer so it can be added.") {
                        Row(Modifier.fillMaxWidth().clickable { showUnparsed = !showUnparsed }, verticalAlignment = Alignment.CenterVertically) {
                            Text("${unparsed.size} found", Modifier.weight(1f), fontSize = 15.sp)
                            Text(if (showUnparsed) "Hide" else "Show", color = teal())
                        }
                        if (showUnparsed) {
                            unparsed.forEach { Text(it, fontSize = 11.sp, color = muted()) }
                            val cb = LocalClipboardManager.current
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { cb.setText(AnnotatedString(unparsed.joinToString("\n\n") { Diagnostics.anonymise(it) })); note = "Copied without numbers" }) { Text("Copy without numbers") }
                                OutlinedButton(onClick = { Repo.clearUnparsed(ctx); unparsed = emptyList() }) { Text("Clear") }
                            }
                        }
                    }
                    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
                    val clipboard = LocalClipboardManager.current
                    if (crash.isNotEmpty()) Section("Problem report", "Copy this and send it to the developer.") {
                        Text(crash.take(600), fontSize = 10.sp, color = muted())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(crash)); note = "Report copied" }) { Text("Copy") }
                            OutlinedButton(onClick = { CrashLog.clear(ctx); crash = "" }) { Text("Clear") }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/** Small dialog to create your own tag. */
@Composable
fun NewTagDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tag") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("e.g. Gym, Pets, Rent") }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Tag picker menu: built-in and custom tags, plus "+ New tag". */
@Composable
fun TagMenu(vm: MainVm, expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val tags by vm.tags.collectAsState()
    var newTag by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        tags.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onPick(c); onDismiss() }) }
        DropdownMenuItem(text = { Text("+ New tag…", color = MaterialTheme.colorScheme.primary) }, onClick = { newTag = true })
    }
    if (newTag) NewTagDialog({ newTag = false }) { n -> vm.addTag(n)?.let { onPick(it) }; newTag = false; onDismiss() }
}

/** Confirms that the ticked bills were paid. */
@Composable
fun PaidDialog(person: String, count: Int, amount: Double, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var mode by remember { mutableStateOf("UPI") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$person paid $count ${if (count == 1) "bill" else "bills"}") },
        text = {
            Column {
                Text(Logic.money(amount), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("UPI", { mode = "UPI" }, selected = mode == "UPI")
                    Pill("Cash", { mode = "Cash" }, selected = mode == "Cash")
                    Pill("Other", { mode = "Other" }, selected = mode == "Other")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(mode) }) { Text("✓") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Rename or delete your own tag; built-in tags can only be hidden from the lists. */
@Composable
fun EditTagDialog(vm: MainVm, tag: String, custom: Boolean, hidden: Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(tag) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tag) },
        text = {
            Column {
                if (custom) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    Text("Renaming also updates every transaction and remembered merchant using this tag.", fontSize = 12.sp, color = muted())
                    Spacer(Modifier.height(8.dp))
                    if (confirmDelete) {
                        Text("Delete \"$tag\"? Its transactions become untagged.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.deleteTag(tag); onDismiss() }) { Text("Yes, delete") }
                    } else TextButton(onClick = { confirmDelete = true }) { Text("Delete tag", color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("Built-in tags can't be renamed. Hide it if you never use it; your old transactions keep it.", fontSize = 13.sp)
                    TextButton(onClick = { vm.toggleHidden(tag); onDismiss() }) { Text(if (hidden) "Show in lists" else "Hide from lists") }
                }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = { if (name.trim() != tag) vm.renameTag(tag, name); onDismiss() }) { Text("✓") }
            else TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Pick one or more people; the result is split equally. "Me" counts as a person. */
@Composable
fun PeopleSelectDialog(vm: MainVm, title: String, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val contacts by vm.contacts.collectAsState()
    val recents by vm.recents.collectAsState()
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val pool = (listOf(Repo.ME) + recents + contacts).distinct()
    val results = pool.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(12)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SearchBox(query, { query = it }, "Search or type a name")
                if (q.isNotEmpty() && pool.none { it.equals(q, true) }) {
                    Text("Add \"$q\"", color = teal(), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { chosen.add(q); query = "" }.padding(vertical = 10.dp))
                }
                (chosen.filter { it !in results } + results).forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { if (n in chosen) chosen.remove(n) else chosen.add(n) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = n in chosen, onCheckedChange = { if (it) chosen.add(n) else chosen.remove(n) })
                        Text(if (n == Repo.ME) "Me" else n, fontSize = 16.sp)
                    }
                }
                if (chosen.isNotEmpty()) Text(if (chosen.size == 1) "All of each spend goes to ${if (chosen[0] == Repo.ME) "you" else chosen[0]}" else "Each spend is split ${chosen.size} ways, equally",
                    fontSize = 12.sp, color = muted(), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onDone(chosen.toList()) }) { Text("✓") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/** First launch: from when should old messages be brought in? Later ones always arrive live. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WelcomeDialog(onRestore: (String, (Boolean) -> Unit) -> Unit, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("choice") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; problem = null
            val text = try { withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: "" } } catch (e: Exception) { CrashLog.record(ctx, e); "" }
            if (Backup.describe(text) == null) { busy = false; problem = "That file is not a Paisa Log backup. Pick the backup file you saved earlier." }
            else onRestore(text) { ok -> busy = false; if (!ok) problem = "Couldn't restore from that file. Nothing was changed." }
        } }
    if (step == "choice") {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Welcome 👋") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Is this your first time, or do you have a backup file from before?", fontWeight = FontWeight.SemiBold)
                    Text("Restoring brings back your transactions, people, tags, name and settings, so there is nothing to set up again.", fontSize = 13.sp, color = muted())
                    if (busy) Text("Restoring…", color = teal())
                    problem?.let { Text(it, fontSize = 13.sp, color = amber()) }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { step = "new" }) { Text("I'm new") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { openBackup.launch(arrayOf("*/*")) }) { Text("Restore backup") } }
        )
        return
    }
    val options = remember { Bills.trackFromOptions(System.currentTimeMillis()) }
    var sel by remember { mutableStateOf(options.getOrNull(1)?.second ?: options.first().second) }
    var picking by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Welcome 👋") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("From which date should I track your spends?", fontWeight = FontWeight.SemiBold)
                Text("Messages from that date go into your Inbox. New ones arrive with a notification and stay in the Inbox until you sort them.", fontSize = 13.sp, color = muted())
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (label, t) -> Pill(label, { sel = t }, selected = sel == t, strong = sel == t) }
                    Pill("Pick a date", { picking = true }, selected = options.none { it.second == sel }, strong = options.none { it.second == sel })
                }
                Text("Tracking from ${fmt.format(java.util.Date(sel))}", fontSize = 13.sp, color = teal())
            }
        },
        confirmButton = { TextButton(onClick = { onPick(sel) }) { Text("Start") } }
    )
    if (picking) {
        val st = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = sel)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { sel = minOf(it, System.currentTimeMillis()) }; picking = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { androidx.compose.material3.DatePicker(state = st) }
    }
}


/** Asked once: the name on your UPI, so transfers between your own accounts are not treated as spends. */
@Composable
fun NameDialog(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone("") },
        title = { Text("Your name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("As it appears on UPI. Payments between your own accounts are then left out of your spends.", fontSize = 13.sp, color = muted())
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name or first name") })
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone("") }) { Text("Skip") } }
    )
}


/** Manual "refund received": the full amount by default, or a smaller one for a part refund. */
@Composable
fun RefundDialog(t: Txn, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var amt by remember { mutableStateOf(t.amount.toString().removeSuffix(".0")) }
    val v = amt.replace(",", "").toDoubleOrNull() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refund received") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${t.merchant} · ${Repo.money(t.amount)}", fontSize = 13.sp, color = muted())
                OutlinedTextField(value = amt, onValueChange = { amt = it }, singleLine = true, label = { Text("Amount refunded ₹") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                Text("The full amount closes it. A smaller amount reduces what you spent.", fontSize = 12.sp, color = muted())
            }
        },
        confirmButton = { TextButton(enabled = v > 0 && v <= t.amount + 0.011, onClick = { onSave(v) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
