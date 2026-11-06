package com.sabharish.splittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val fullDate = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
fun fullDate(ms: Long): String = fullDate.format(Date(ms))

fun kindLabel(kind: String) = when (kind) {
    "SPEND" -> "Card"
    "UPI_OUT" -> "UPI sent"
    "UPI_IN" -> "UPI received"
    "REFUND" -> "Refund"
    "PAYMENT" -> "Card bill"
    else -> kind
}

private fun firstNames(people: List<String>): List<String> {
    val firsts = people.map { it.trim().substringBefore(" ") }
    return people.mapIndexed { i, p -> if (firsts.count { it == firsts[i] } > 1) p else firsts[i] }
}

private fun daysSince(ms: Long) = ((System.currentTimeMillis() - ms) / 86_400_000L).toInt()

@Composable
private fun AppCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

/** Merchant (with its friendly name) on the left, amount on the right, one detail line below. */
@Composable
private fun TxnHeader(t: Txn, aliases: Map<String, String>, incoming: Boolean = false, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(Repo.title(t.merchant, aliases), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${Repo.acct(t.bank, t.card)} · ${Repo.shortDate(t.txnDate)} · ${kindLabel(t.kind)}", fontSize = 13.sp, color = muted())
        }
        Text(
            (if (incoming) "+" else "") + Repo.money(t.amount),
            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (incoming) green() else MaterialTheme.colorScheme.onSurface
        )
        trailing()
    }
}

// ======================= INBOX =======================

@Composable
fun InboxScreen(vm: MainVm, undoable: (Long, String) -> Unit) {
    val allSpends by vm.inbox.collectAsState()
    val allMoney by vm.moneyIn.collectAsState()
    val allAuto by vm.autoTagged.collectAsState()
    val allFailed by vm.failed.collectAsState()
    var section by remember { mutableIntStateOf(0) }
    var via by remember { mutableStateOf("All") }
    var q by remember(Epoch.value) { mutableStateOf("") }
    val aliasMap by vm.aliases.collectAsState()
    fun List<Txn>.search() = if (q.isBlank()) this else filter { Filters.textMatches(it, emptyList(), aliasMap, q) }
    val spends = allSpends.search(); val money = allMoney.search(); val auto = allAuto.search(); val failed = allFailed.search()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Spent · ${spends.size}", { section = 0 }, selected = section == 0)
        Pill("Received · ${money.size}", { section = 1 }, selected = section == 1)
        Pill("Auto-sorted · ${auto.size}", { section = 2 }, selected = section == 2)
        if (failed.isNotEmpty() || section == 3) Pill("Refund watch · ${failed.size}", { section = 3 }, selected = section == 3)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search name, amount or message") },
        trailingIcon = { if (q.isNotEmpty()) TextButton(onClick = { q = "" }) { Text("✕") } }
    )
    Spacer(Modifier.height(8.dp))
    if (q.isNotBlank() && spends.isEmpty() && money.isEmpty() && auto.isEmpty() && failed.isEmpty())
        Text("Nothing matches \"${q.trim()}\".", color = muted(), fontSize = 15.sp)
    when (section) {
        0 -> {
            val cards = spends.count { it.kind == "SPEND" }
            val upi = spends.size - cards
            if (cards > 0 && upi > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("All", { via = "All" }, selected = via == "All")
                    Pill("Card · $cards", { via = "Card" }, selected = via == "Card")
                    Pill("UPI · $upi", { via = "UPI" }, selected = via == "UPI")
                }
                Spacer(Modifier.height(10.dp))
            }
            SpendList(vm, when (via) { "Card" -> spends.filter { it.kind == "SPEND" }; "UPI" -> spends.filter { it.kind != "SPEND" }; else -> spends }, undoable)
        }
        1 -> MoneyInList(vm, money)
        2 -> AutoList(vm, auto)
        else -> FailedList(vm, failed)
    }
}

/** Compact row shown while selecting: tick, merchant, amount. */
@Composable
private fun SelectRow(t: Txn, aliases: Map<String, String>, checked: Boolean, onToggle: () -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(Repo.title(t.merchant, aliases), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${Repo.shortDate(t.txnDate)} · ${if (t.category.isBlank()) "no tag" else t.category}", fontSize = 12.sp, color = muted())
            }
            Text(Repo.money(t.amount), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Actions for the ticked transactions: tag, people, mine, add similar ones. */
@Composable
private fun BulkBar(vm: MainVm, picked: List<Txn>, onSimilar: () -> Unit, onSelectAll: () -> Unit, onDone: () -> Unit) {
    var tagMenu by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf(false) }
    AppCard {
        Text("${picked.size} selected · ${Repo.money(picked.sumOf { it.amount })}", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Pill("Tag ▾", { if (picked.isNotEmpty()) tagMenu = true }, strong = picked.isNotEmpty())
                TagMenu(vm, tagMenu, { tagMenu = false }) { vm.bulkCategory(picked, it); onDone() }
            }
            Pill("People…", { if (picked.isNotEmpty()) people = true }, strong = picked.isNotEmpty())
            Pill("Mine", { if (picked.isNotEmpty()) { vm.bulkPeople(picked, listOf(Repo.ME)); onDone() } })
            Pill("+ Similar", onSimilar)
            Pill("All", onSelectAll)
            Pill("Done ✕", onDone)
        }
    }
    if (people) PeopleSelectDialog(vm, "Who were these for?", { people = false }) { vm.bulkPeople(picked, it); people = false; onDone() }
}

@Composable
private fun SpendList(vm: MainVm, items: List<Txn>, undoable: (Long, String) -> Unit) {
    val recents by vm.recents.collectAsState()
    var sheetFor by remember { mutableStateOf<Txn?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    if (items.isEmpty()) {
        Text("Inbox zero ✨ New card and UPI payments appear here on their own.", color = muted(), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.importHistory(365) }) { Text("Import last 12 months of messages") }
        return
    }
    val aliasesNow by vm.aliases.collectAsState()
    val picked = remember(Epoch.value) { mutableStateListOf<Long>() }
    var selecting by remember(Epoch.value) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting) { picked.clear(); selecting = false }
    val pickedTxns = items.filter { it.id in picked }
    if (selecting) {
        BulkBar(vm, pickedTxns,
            onSimilar = { pickedTxns.forEach { p -> Logic.similar(p, items).forEach { if (it.id !in picked) picked.add(it.id) } } },
            onSelectAll = { picked.clear(); picked.addAll(items.map { it.id }) },
            onDone = { picked.clear(); selecting = false })
        Spacer(Modifier.height(8.dp))
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tap names, then ✓. More… to split.", fontSize = 13.sp, color = muted(), modifier = Modifier.weight(1f))
            TextButton(onClick = { selecting = true }) { Text("Select") }
            if (items.size > 1) TextButton(onClick = { confirmAll = true }) { Text("All mine") }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { t ->
            if (selecting) SelectRow(t, aliasesNow, t.id in picked) { if (t.id in picked) picked.remove(t.id) else picked.add(t.id) }
            else SpendCard(t, vm, recents, undoable, onSimilar = { selecting = true; picked.clear(); picked.add(t.id); Logic.similar(t, items).forEach { picked.add(it.id) } }) { sheetFor = it }
        }
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = { undoable(t.id, it) }) }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Mark all ${items.size} as yours?") },
            text = { Text("Every untagged item becomes your own spend. You can still change one later from the Transactions tab.") },
            confirmButton = { TextButton(onClick = { vm.tagAllMine(items); confirmAll = false }) { Text("Mark all mine") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SpendCard(t: Txn, vm: MainVm, recents: List<String>, undoable: (Long, String) -> Unit, onSimilar: () -> Unit = {}, onMore: (Txn) -> Unit) {
    val aliases by vm.aliases.collectAsState()
    val sug by produceState<Rule?>(null, t.id) { value = vm.suggestion(t) }
    var catMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    fun quick(shares: List<NewShare>, label: String) {
        vm.tag(t, shares, t.category, false, false)
        undoable(t.id, label)
    }
    AppCard {
        TxnHeader(t, aliases) {
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Select all from this merchant") }, onClick = { moreMenu = false; onSimilar() })
                    DropdownMenuItem(text = { Text("Rename merchant") }, onClick = { moreMenu = false; rename = true })
                    DropdownMenuItem(text = { Text("Payment failed, expecting refund") }, onClick = { moreMenu = false; vm.markFailed(t) })
                    DropdownMenuItem(text = { Text("Skip, not a real spend") }, onClick = { moreMenu = false; vm.ignore(t) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Pill(if (t.category.isBlank()) "+ Add tag" else t.category, { catMenu = true })
            TagMenu(vm, catMenu, { catMenu = false }) { vm.setCategory(t, it) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Whose spend? Tap one or more", fontSize = 13.sp, color = muted())
        Spacer(Modifier.height(6.dp))
        val sel = remember(Epoch.value) { mutableStateListOf<String>() }
        fun toggle(p: String) { if (p in sel) sel.remove(p) else sel.add(p) }
        val s = sug
        val asBefore = if (s != null && s.action == "PEOPLE") s.people.split(",").filter { it.isNotBlank() } else emptyList()
        val chips = (listOf(Repo.ME) + recents.filter { it !in asBefore }.take(4) + sel.filter { it != Repo.ME && it !in recents }).distinct()
        val ordered = Logic.selectedFirst(chips, sel.toList())
        val labelOf = chips.zip(firstNames(chips)).toMap().mapValues { if (it.key == Repo.ME) "Me" else it.value }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.filter { it in sel }.forEach { p -> Pill(labelOf[p] ?: p, { toggle(p) }, selected = true) }
            if (asBefore.isNotEmpty() && sel.toList() != asBefore) {
                val label = firstNames(asBefore.filter { it != Repo.ME }).joinToString(" + ").ifEmpty { "You" } +
                    if (asBefore.contains(Repo.ME)) " & you" else ""
                Pill("As before: $label", { sel.clear(); sel.addAll(asBefore) })
            }
            ordered.filter { it !in sel }.forEach { p -> Pill(labelOf[p] ?: p, { toggle(p) }) }
            Pill("More…", { onMore(t) })
        }
        if (sel.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Logic.selectionSummary(t.amount, sel.toList()), Modifier.weight(1f), fontSize = 15.sp)
                FilledIconButton(onClick = {
                    val people = sel.toList()
                    val shares = if (people.size == 1) listOf(NewShare(people[0], t.amount, people[0] == Repo.ME))
                    else Repo.equalShares(t.amount, people, false)
                    quick(shares, if (people == listOf(Repo.ME)) "Tagged as yours" else "Tagged")
                }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Check, contentDescription = "Save") }
            }
        }
    }
    if (rename) AliasDialog(t.merchant, aliases[Repo.aliasKey(t.merchant).removePrefix("alias|")] ?: "", { rename = false }) { vm.setAlias(t.merchant, it); rename = false }
}

@Composable
private fun MoneyInList(vm: MainVm, items: List<Txn>) {
    val pending by vm.pending.collectAsState()
    val recents by vm.recents.collectAsState()
    val contacts by vm.contacts.collectAsState()
    var pickFor by remember { mutableStateOf<Txn?>(null) }
    var linkFor by remember { mutableStateOf<Txn?>(null) }
    if (items.isEmpty()) {
        Text("No money-in to review. UPI payments you receive, and refunds, appear here.", color = muted(), fontSize = 15.sp)
        return
    }
    val owed = pending.groupBy { it.person }.entries.sortedByDescending { e -> e.value.sumOf { it.amount - it.settled } }.map { it.key }
    val known = (owed + recents + contacts).distinct()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { t -> MoneyCard(t, vm, owed, known, onPick = { pickFor = it }, onLink = { linkFor = it }) }
    }
    pickFor?.let { t ->
        PersonPickerDialog(vm, "Repayment from…", onDismiss = { pickFor = null }, onPick = { vm.repaidBy(t, it); pickFor = null })
    }
    linkFor?.let { t -> LinkRefundDialog(vm, t) { linkFor = null } }
}

@Composable
private fun MoneyCard(t: Txn, vm: MainVm, owed: List<String>, known: List<String>, onPick: (Txn) -> Unit, onLink: (Txn) -> Unit) {
    val aliases by vm.aliases.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    val guess by produceState<Txn?>(null, t.id) { value = if (t.kind == "REFUND") vm.suggestRefund(t) else null }
    AppCard {
        TxnHeader(t, aliases, incoming = true)
        Spacer(Modifier.height(12.dp))
        if (t.kind == "UPI_IN") {
            val match = Repo.bestMatch(t.merchant, known)
            Text("Is someone paying you back?", fontSize = 13.sp, color = muted())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (match != null) Pill("Repayment from $match", { confirm = match }, strong = true)
                val others = owed.filter { it != match }.take(5)
                val labels = firstNames(others)
                others.forEachIndexed { i, p -> Pill(labels[i], { confirm = p }) }
                Pill("Someone else…", { onPick(t) })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onLink(t) }) { Text("It's a refund") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.ignore(t) }) { Text("Ignore", color = muted()) }
            }
        } else {
            val g = guess
            if (g != null) {
                Text("Looks like a refund for:", fontSize = 13.sp, color = muted())
                Text("${Repo.title(g.merchant, aliases)} · ${Repo.money(g.amount)} · ${fullDate(g.txnDate)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.linkRefund(t.id, g.id) }) { Text("Yes, close it") }
                    OutlinedButton(onClick = { onLink(t) }) { Text("Pick another") }
                }
            } else {
                Text("No matching purchase found. Link it so the books stay clear.", fontSize = 13.sp, color = muted())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onLink(t) }) { Text("Link to purchase") }
                    OutlinedButton(onClick = { vm.keepAsRefund(t.id) }) { Text("Just keep") }
                }
            }
            Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { vm.ignore(t) }) { Text("Ignore", color = muted()) } }
        }
    }
    confirm?.let { person ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Repayment from $person?") },
            text = { Text("${Repo.money(t.amount)} will clear what $person owes you, oldest first.") },
            confirmButton = { TextButton(onClick = { vm.repaidBy(t, person); confirm = null }) { Text("Yes, mark paid") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AutoList(vm: MainVm, items: List<Txn>) {
    val aliases by vm.aliases.collectAsState()
    var sheetFor by remember { mutableStateOf<Txn?>(null) }
    if (items.isEmpty()) {
        Text("Nothing was tagged automatically yet. Items from merchants you tag with Remember on appear here, so you can change them.", color = muted(), fontSize = 15.sp)
        return
    }
    Text("Counted as yours automatically. Change any that were for someone else.", fontSize = 13.sp, color = muted())
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { t ->
            AppCard {
                TxnHeader(t, aliases)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.category.isNotBlank()) Pill(t.category, {})
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { sheetFor = t }) { Text("Change") }
                }
            }
        }
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
}

@Composable
private fun FailedList(vm: MainVm, items: List<Txn>) {
    val aliases by vm.aliases.collectAsState()
    if (items.isEmpty()) {
        Text("No failed payments. If a payment fails, open the ⋮ menu on it and choose “Payment failed”. It closes by itself when the refund SMS arrives.", color = muted(), fontSize = 15.sp)
        return
    }
    Text("Waiting for the bank to reverse these. They close automatically when the refund arrives.", fontSize = 13.sp, color = muted())
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { t ->
            val d = daysSince(t.txnDate)
            AppCard {
                TxnHeader(t, aliases)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (d > 10) "Waiting $d days. Worth checking with the bank." else "Waiting $d days",
                    fontSize = 13.sp, color = if (d > 10) amber() else muted()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.unfail(t.id) }) { Text("It didn't fail") }
            }
        }
    }
}

// ======================= PENDING =======================

@Composable
fun PendingScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val aliases by vm.aliases.collectAsState()
    val rows by vm.pending.collectAsState()
    var settleFor by remember { mutableStateOf<String?>(null) }
    var remindFor by remember { mutableStateOf<String?>(null) }
    val grouped = rows.groupBy { it.person }
    val total = rows.sumOf { it.amount - it.settled }
    val ticks = remember(Epoch.value) { mutableMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<Long>>() }

    AppCard {
        Text("Owed to you", fontSize = 13.sp, color = muted())
        Text(Repo.money(total), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (total > 0) teal() else muted())
        Text("${grouped.size} people · ${rows.size} items", fontSize = 13.sp, color = muted())
    }
    Spacer(Modifier.height(12.dp))
    if (rows.isEmpty()) Text("Nobody owes you anything right now.", color = muted(), fontSize = 15.sp)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(grouped.entries.sortedByDescending { e -> e.value.sumOf { it.amount - it.settled } }.toList(), key = { it.key }) { e ->
            val owed = e.value.sumOf { it.amount - it.settled }
            val days = daysSince(e.value.minOf { it.txnDate })
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(e.key, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("${e.value.size} items · oldest $days days", fontSize = 13.sp,
                            color = if (days > 30) MaterialTheme.colorScheme.error else if (days > 14) amber() else muted())
                    }
                    Text(Repo.money(owed), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                val ticked = ticks.getOrPut(e.key) { mutableStateListOf<Long>() }
                e.value.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { if (r.shareId in ticked) ticked.remove(r.shareId) else ticked.add(r.shareId) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.shareId in ticked, onCheckedChange = { if (it) ticked.add(r.shareId) else ticked.remove(r.shareId) })
                        Text("${Repo.shortDate(r.txnDate)} · ${Repo.nameOnly(r.merchant, aliases)}", Modifier.weight(1f), fontSize = 14.sp)
                        Text(Repo.money(r.amount - r.settled), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { remindFor = e.key }) { Text("Remind") }
                    OutlinedButton(onClick = { settleFor = e.key }) {
                        Text(if (ticked.isEmpty()) "Mark all paid" else "Mark ${ticked.size} paid · ${Logic.money(Logic.dueOf(e.value, ticked.toSet()))}")
                    }
                }
            }
        }
    }
    settleFor?.let { person ->
        val mine = rows.filter { it.person == person }
        val picked = (ticks[person]?.toSet() ?: emptySet()).intersect(mine.map { it.shareId }.toSet())
        if (picked.isEmpty()) {
            SettleDialog(person, mine.sumOf { it.amount - it.settled }, onDismiss = { settleFor = null }) { amt, mode -> vm.settle(person, amt, mode); settleFor = null }
        } else {
            PaidDialog(person, picked.size, Logic.dueOf(mine, picked), onDismiss = { settleFor = null }) { mode ->
                vm.settleShares(person, picked, mode); ticks[person]?.clear(); settleFor = null
            }
        }
    }
    remindFor?.let { person ->
        ReminderDialog(person, { upi -> Reminder.build(person, rows.filter { it.person == person }, aliases, upi, System.currentTimeMillis()) }) { remindFor = null }
    }
}

// ======================= ALL TRANSACTIONS =======================

@Composable
fun AllScreen(vm: MainVm) {
    val aliases by vm.aliases.collectAsState()
    val txns by vm.all.collectAsState()
    val shares by vm.shares.collectAsState()
    var f by remember { mutableStateOf(TxnFilter()) }
    var sheetFor by remember { mutableStateOf<Txn?>(null) }
    val tags by vm.tags.collectAsState()
    val byTxn = shares.groupBy { it.txnId }
    val now = System.currentTimeMillis()
    val shown = txns.filter { Filters.matches(it, byTxn[it.id] ?: emptyList(), aliases, f, now) }
    OutlinedTextField(
        value = f.query, onValueChange = { f = f.copy(query = it) }, singleLine = true,
        placeholder = { Text("Search name, amount, tag, person or message") }, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPill("Type", Filters.types, f.type) { f = f.copy(type = it) }
        FilterPill("Date", Filters.ranges, f.range) { f = f.copy(range = it) }
        FilterPill("Tag", listOf("Any", "Untagged") + tags, if (f.tagState == "Untagged") "Untagged" else f.tag.ifBlank { "Any" }) {
            f = when (it) { "Any" -> f.copy(tagState = "Any", tag = ""); "Untagged" -> f.copy(tagState = "Untagged", tag = ""); else -> f.copy(tagState = "Any", tag = it) }
        }
        FilterPill("Status", Filters.statuses, f.status) { f = f.copy(status = it) }
        if (f.active) Pill("Clear ✕", { f = TxnFilter() })
    }
    Spacer(Modifier.height(6.dp))
    Text("${shown.size} transactions · ${Logic.money(shown.filter { it.kind == "SPEND" || it.kind == "UPI_OUT" }.sumOf { it.amount })} spent",
        fontSize = 12.sp, color = muted())
    val picked = remember(Epoch.value) { mutableStateListOf<Long>() }
    var selecting by remember(Epoch.value) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting) { picked.clear(); selecting = false }
    val pickedTxns = shown.filter { it.id in picked }
    if (selecting) {
        Spacer(Modifier.height(6.dp))
        BulkBar(vm, pickedTxns,
            onSimilar = { pickedTxns.forEach { p -> Logic.similar(p, shown).forEach { if (it.id !in picked) picked.add(it.id) } } },
            onSelectAll = { picked.clear(); picked.addAll(shown.map { it.id }) },
            onDone = { picked.clear(); selecting = false })
    } else TextButton(onClick = { selecting = true }) { Text("Select several…") }
    Spacer(Modifier.height(4.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(shown, key = { it.id }) { t ->
            if (selecting) SelectRow(t, aliases, t.id in picked) { if (t.id in picked) picked.remove(t.id) else picked.add(t.id) }
            else TxnRow(t, byTxn[t.id] ?: emptyList(), vm, aliases) { sheetFor = it }
        }
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
}

/** A pill that opens a menu; highlighted when something other than "Any" is chosen. */
@Composable
private fun FilterPill(title: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val on = selected != options.first() && selected != "Any" && selected != "Any time"
    Box {
        Pill(if (on) selected else "$title ▾", { open = true }, selected = on)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
private fun TxnRow(t: Txn, ss: List<Share>, vm: MainVm, aliases: Map<String, String>, onChange: (Txn) -> Unit) {
    var catMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val incoming = t.kind == "UPI_IN" || t.kind == "REFUND"
    val spendLike = t.kind == "SPEND" || t.kind == "UPI_OUT"
    val state = when (t.status) {
        "INBOX" -> "Needs tagging"
        "SELF" -> "Own transfer"
        "IGNORED" -> "Skipped"
        "REVERSED" -> "Reversed"
        "FAILED" -> "Failed · waiting for refund"
        "REPAID" -> "Repayment"
        "BILLPAY" -> "Card bill payment"
        else -> ""
    }
    AppCard {
        TxnHeader(t, aliases, incoming) {
            if (spendLike) Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    if (t.status == "TAGGED" || t.status == "INBOX") DropdownMenuItem(text = { Text("Change who / tag") }, onClick = { moreMenu = false; onChange(t) })
                    DropdownMenuItem(text = { Text("Rename merchant") }, onClick = { moreMenu = false; rename = true })
                    if (t.status != "FAILED" && t.status != "REVERSED") DropdownMenuItem(text = { Text("Payment failed, expecting refund") }, onClick = { moreMenu = false; vm.markFailed(t) })
                }
            }
        }
        if (state.isNotEmpty()) Text(state, fontSize = 13.sp, color = if (t.status == "INBOX" || t.status == "FAILED") amber() else muted())
        if (t.note.isNotBlank()) Text(t.note, fontSize = 13.sp, color = muted())
        if (spendLike) {
            Spacer(Modifier.height(6.dp))
            Box {
                Pill(if (t.category.isBlank()) "+ Add tag" else t.category, { catMenu = true })
                TagMenu(vm, catMenu, { catMenu = false }) { vm.setCategory(t, it) }
            }
        }
        val live = ss.filter { it.amount > 0.004 }
        if (live.isNotEmpty()) Spacer(Modifier.height(6.dp))
        live.forEach { s ->
            val label = if (s.person == Repo.ME) "You" else s.person
            val st = if (s.person == Repo.ME) "your share" else if (s.settled >= s.amount - 0.005) "paid" else "owes ${Repo.money(s.amount - s.settled)}"
            Text("$label · ${Repo.money(s.amount)} · $st", fontSize = 14.sp)
        }
        if (live.any { it.person != Repo.ME }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.splitAdded, onCheckedChange = { vm.toggleSplitAdded(t) })
                Text("Added to PhonePe Split", fontSize = 14.sp)
            }
        }
    }
    if (rename) AliasDialog(t.merchant, aliases[Repo.aliasKey(t.merchant).removePrefix("alias|")] ?: "", { rename = false }) { vm.setAlias(t.merchant, it); rename = false }
}

// ======================= SUMMARY =======================

@Composable
fun SummaryScreen(vm: MainVm) {
    val rows by vm.summary.collectAsState()
    var offset by remember { mutableIntStateOf(0) }
    val cal = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    val ym = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    fun monthOf(ms: Long): Int {
        val c = Calendar.getInstance(); c.timeInMillis = ms
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
    }
    val label = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
    val inMonth = rows.filter { monthOf(it.txnDate) == ym }
    val mine = inMonth.filter { it.person == Repo.ME }.sumOf { it.shareAmt }
    val forOthers = inMonth.filter { it.person != Repo.ME }.sumOf { it.shareAmt }
    val receivable = rows.filter { it.person != Repo.ME }.sumOf { it.shareAmt - it.settled }.coerceAtLeast(0.0)
    val byCat = inMonth.filter { it.person == Repo.ME && it.shareAmt > 0.004 }
        .groupBy { it.category.ifBlank { "Untagged" } }
        .mapValues { e -> e.value.sumOf { it.shareAmt } }
        .entries.sortedByDescending { it.value }
    val byCard = inMonth.groupBy { it.card }.entries.sortedByDescending { e -> e.value.sumOf { it.shareAmt } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset -= 1 }) { Text("‹ Prev") }
                Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                TextButton(onClick = { if (offset < 0) offset += 1 }, enabled = offset < 0) { Text("Next ›") }
            }
        }
        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("My spend", fontSize = 13.sp, color = muted())
                        Text(Repo.money(mine), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Paid for others", fontSize = 13.sp, color = muted())
                        Text(Repo.money(forOthers), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Still to recover (all time): ${Repo.money(receivable)}", fontSize = 14.sp, color = teal(), fontWeight = FontWeight.SemiBold)
            }
        }
        if (byCat.isNotEmpty()) {
            item {
                AppCard {
                    Text("Where my money went", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    val top = byCat.first().value.coerceAtLeast(1.0)
                    byCat.take(8).forEach { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.key, fontSize = 14.sp)
                            Text(Repo.money(e.value), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier.padding(top = 3.dp, bottom = 10.dp)
                                .fillMaxWidth((e.value / top).toFloat().coerceIn(0.03f, 1f))
                                .height(8.dp)
                                .background(teal(), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
        if (byCard.isNotEmpty()) {
            item {
                AppCard {
                    Text("By card / account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    byCard.forEach { e ->
                        val m = e.value.filter { it.person == Repo.ME }.sumOf { it.shareAmt }
                        val o = e.value.filter { it.person != Repo.ME }.sumOf { it.shareAmt }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("••${e.key}", fontSize = 14.sp)
                            Text("mine ${Repo.money(m)} · others ${Repo.money(o)}", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (inMonth.isEmpty()) item { Text("Nothing tagged for this month yet.", color = muted(), fontSize = 15.sp) }
    }
}
