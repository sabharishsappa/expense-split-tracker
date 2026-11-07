package com.sabharish.splittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
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

fun fullDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(ms))

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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun AppCard(onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            (if (onLongClick != null || onClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier).padding(16.dp)
        ) { content() }
    }
}

/** Merchant (with its friendly name) on the left, amount on the right, one detail line below. */
@Composable
private fun TxnHeader(t: Txn, aliases: Map<String, String>, incoming: Boolean = false, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(Repo.title(t.merchant, aliases), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${Repo.acct(t.bank, t.card)} · ${Repo.shortDate(t.txnDate)} · ${kindLabel(t.kind)}", fontSize = 13.sp, color = muted())
        }
        Text(
            (if (incoming) "+" else "") + Repo.money(t.amount),
            fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (incoming) green() else MaterialTheme.colorScheme.onSurface
        )
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

// ======================= INBOX =======================

@Composable
fun InboxScreen(vm: MainVm, undoable: (Long, String) -> Unit, q: String, section: Int, onSection: (Int) -> Unit) {
    val allSpends by vm.inbox.collectAsState()
    val allMoney by vm.moneyIn.collectAsState()
    val allAuto by vm.autoTagged.collectAsState()
    val allFailed by vm.failed.collectAsState()
    var via by remember { mutableStateOf("All") }
    val aliasMap by vm.aliases.collectAsState()
    fun List<Txn>.search() = if (q.isBlank()) this else filter { Filters.textMatches(it, emptyList(), aliasMap, q) }
    val spends = allSpends.search(); val money = allMoney.search(); val auto = allAuto.search(); val failed = allFailed.search()
    val tabs = buildList {
        add("Spent" to spends.size); add("Received" to money.size); add("Auto" to auto.size)
        if (failed.isNotEmpty() || section == 3) add("Refunds" to failed.size)
    }
    androidx.compose.material3.ScrollableTabRow(
        selectedTabIndex = section.coerceAtMost(tabs.size - 1), edgePadding = 0.dp,
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) {
        tabs.forEachIndexed { i, (name, n) ->
            androidx.compose.material3.Tab(selected = section == i, onClick = { onSection(i) },
                text = { Text(if (n > 0) "$name · $n" else name, fontSize = 14.sp) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (q.isNotBlank() && spends.isEmpty() && money.isEmpty() && auto.isEmpty() && failed.isEmpty())
        Text("Nothing matches \"${q.trim()}\".", color = muted(), fontSize = 15.sp)
    when (section) {
        0 -> {
            val cards = spends.count { it.kind == "SPEND" }
            val upi = spends.size - cards
            if (cards > 0 && upi > 0) {
                Segmented(listOf("All", "Card · $cards", "UPI · $upi"),
                    when (via) { "Card" -> "Card · $cards"; "UPI" -> "UPI · $upi"; else -> "All" },
                    { via = it.substringBefore(" ") })
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

/** Group edit: choose a tag and/or people in one row, then the single ✓ applies it to every ticked bill. */
@Composable
private fun BulkBar(vm: MainVm, picked: List<Txn>, onSelectAll: () -> Unit, onDone: () -> Unit) {
    val recents by vm.recents.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val groups = remember(Epoch.value) { Groups.decode(Repo.getPref(ctx, "groups", "")) }
    var tagMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf("") }
    val people = remember { mutableStateListOf<String>() }
    val chips = (listOf(Repo.ME) + recents.take(3) + people.filter { it != Repo.ME && it !in recents.take(3) }).distinct()
    val ready = picked.isNotEmpty() && (tag.isNotBlank() || people.isNotEmpty())
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (picked.isEmpty()) "Select bills" else "${picked.size} selected · ${Repo.money(picked.sumOf { it.amount })}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSelectAll) { Text("Select all") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Pill(if (tag.isBlank()) "Tag ▾" else "$tag ▾", { tagMenu = true }, selected = tag.isNotBlank(), compact = true)
                    TagMenu(vm, tagMenu, { tagMenu = false }) { tag = it; tagMenu = false }
                }
                Logic.selectedFirst(chips, people.toList()).forEach { p ->
                    Pill(if (p == Repo.ME) "Me" else p.substringBefore(" "), { if (p in people) people.remove(p) else people.add(p) }, selected = p in people, compact = true)
                }
                groups.forEach { g ->
                    val on = g.people.all { it in people }
                    Pill(g.name, {
                        if (on) people.removeAll(g.people.toSet()) else (listOf(Repo.ME) + g.people).forEach { if (it !in people) people.add(it) }
                    }, selected = on, compact = true)
                }
                Pill("More", { more = true }, compact = true)
            }
            Spacer(Modifier.size(8.dp))
            FilledIconButton(enabled = ready, modifier = Modifier.size(44.dp), onClick = { vm.bulkApply(picked, tag, people.toList()); onDone() }) {
                Icon(Icons.Default.Check, contentDescription = "Apply to all")
            }
        }
        if (ready) {
            Spacer(Modifier.height(6.dp))
            Text(Logic.bulkSummary(picked.size, picked.sumOf { it.amount }, tag, people.toList()), fontSize = 12.sp, color = muted())
        }
    }
    if (more) PeopleSelectDialog(vm, "Who were these for?", { more = false }) { chosen -> chosen.forEach { if (it !in people) people.add(it) }; more = false }
}

@Composable
private fun SpendList(vm: MainVm, items: List<Txn>, undoable: (Long, String) -> Unit) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val recents by vm.recents.collectAsState()
    var sheetFor by remember { mutableStateOf<Pair<Txn, List<String>>?>(null) }
    // chosen people live here, not inside each card, so scrolling or searching never loses them
    val chosenBy = remember(Epoch.value) { androidx.compose.runtime.mutableStateMapOf<Long, List<String>>() }
    var confirmAll by remember { mutableStateOf(false) }
    if (items.isEmpty()) {
        val smsCtx = androidx.compose.ui.platform.LocalContext.current
        val smsOk = remember(Perm.tick) { Repo.hasPermission(smsCtx, android.Manifest.permission.READ_SMS) }
        if (!smsOk) { SmsBanner(); return }
        Text("You're all caught up. New payments will show up here.", color = muted(), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.importDays(365) }) { Text("Import last 12 months of messages") }
        return
    }
    val aliasesNow by vm.aliases.collectAsState()
    val groups = remember(Epoch.value) { Groups.decode(Repo.getPref(appCtx, "groups", "")) }
    val picked = remember(Epoch.value) { mutableStateListOf<Long>() }
    var selecting by remember(Epoch.value) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting) { picked.clear(); selecting = false }
    val pickedTxns = items.filter { it.id in picked }
    if (selecting) {
        BulkBar(vm, pickedTxns,
            onSelectAll = { picked.clear(); picked.addAll(items.map { it.id }) },
            onDone = { picked.clear(); selecting = false })
        Spacer(Modifier.height(8.dp))
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selecting = true }) { Text("Select") }
            if (items.size > 1) TextButton(onClick = { confirmAll = true }) { Text("All mine") }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
        items(items, key = { it.id }) { t ->
            if (selecting) SelectRow(t, aliasesNow, t.id in picked) { if (t.id in picked) picked.remove(t.id) else picked.add(t.id) }
            else SpendCard(t, vm, recents, groups, undoable, sel = chosenBy[t.id] ?: emptyList(), setSel = { chosenBy[t.id] = it }, onSimilar = { selecting = true; picked.clear(); picked.add(t.id); Logic.similar(t, items).forEach { picked.add(it.id) } }, onSelect = { selecting = true; picked.clear(); picked.add(t.id) }) { tx, chosen -> sheetFor = tx to chosen }
        }
    }
    sheetFor?.let { (t, chosen) -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = { undoable(t.id, it) }, initial = chosen, groups = groups) }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Mark all ${items.size} as yours?") },
            text = { Text("All untagged payments will be marked as yours. You can edit any of them later.") },
            confirmButton = { TextButton(onClick = { vm.tagAllMine(items); confirmAll = false }) { Text("Mark all mine") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SpendCard(t: Txn, vm: MainVm, recents: List<String>, groups: List<Groups.Group>, undoable: (Long, String) -> Unit, sel: List<String>, setSel: (List<String>) -> Unit, onSimilar: () -> Unit = {}, onSelect: () -> Unit = {}, onMore: (Txn, List<String>) -> Unit) {
    val aliases by vm.aliases.collectAsState()
    val sug by produceState<Rule?>(null, t.id) { value = vm.suggestion(t) }
    var catMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    fun quick(shares: List<NewShare>, label: String) {
        vm.tag(t, shares, t.category, false, false)
        undoable(t.id, label)
    }
    AppCard(onLongClick = onSelect) {
        TxnHeader(t, aliases) {
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Select same merchant") }, onClick = { moreMenu = false; onSimilar() })
                    DropdownMenuItem(text = { Text("Rename merchant") }, onClick = { moreMenu = false; rename = true })
                    DropdownMenuItem(text = { Text("Payment failed") }, onClick = { moreMenu = false; vm.markFailed(t) })
                    if (t.kind == "UPI_OUT") DropdownMenuItem(text = { Text("Own transfer") }, onClick = { moreMenu = false; vm.ownTransfer(t) })
                    DropdownMenuItem(text = { Text("Not a spend (skip)") }, onClick = { moreMenu = false; vm.ignore(t) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Pill(if (t.category.isBlank()) "+ Add tag" else t.category, { catMenu = true })
            TagMenu(vm, catMenu, { catMenu = false }) { vm.setCategory(t, it) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Whose spend?", fontSize = 13.sp, color = muted())
        Spacer(Modifier.height(6.dp))
        fun toggle(p: String) { setSel(if (p in sel) sel - p else sel + p) }
        val s = sug
        val asBefore = if (s != null && s.action == "PEOPLE") s.people.split(",").filter { it.isNotBlank() } else emptyList()
        val chips = (listOf(Repo.ME) + recents.filter { it !in asBefore }.take(4) + sel.filter { it != Repo.ME && it !in recents }).distinct()
        val ordered = Logic.selectedFirst(chips, sel.toList())
        val labelOf = chips.zip(firstNames(chips)).toMap().mapValues { if (it.key == Repo.ME) "Me" else it.value }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.filter { it in sel }.forEach { p -> Pill(labelOf[p] ?: p, { toggle(p) }, selected = true) }
            if (asBefore.isNotEmpty() && sel != asBefore) {
                val label = firstNames(asBefore.filter { it != Repo.ME }).joinToString(" + ").ifEmpty { "You" } +
                    if (asBefore.contains(Repo.ME)) " & you" else ""
                Pill("As before: $label", { setSel(asBefore) })
            }
            ordered.filter { it !in sel }.forEach { p -> Pill(labelOf[p] ?: p, { toggle(p) }) }
            groups.forEach { g ->
                val on = g.people.all { it in sel }
                Pill(g.name, { setSel(if (on) sel - g.people.toSet() else (sel + Repo.ME + g.people).distinct()) }, selected = on)
            }
            Pill("More…", { onMore(t, sel.toList()) })
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
        Text("No money received to review.", color = muted(), fontSize = 15.sp)
        return
    }
    val owed = pending.groupBy { it.person }.entries.sortedByDescending { e -> e.value.sumOf { it.amount - it.settled } }.map { it.key }
    val known = (owed + recents + contacts).distinct()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
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
    var borrowPick by remember { mutableStateOf(false) }
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
            Spacer(Modifier.height(10.dp))
            Text("Or it was…", fontSize = 13.sp, color = muted())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Borrowed — I'll pay back", { borrowPick = true })
                Pill("Salary", { vm.receivedFor(t, "Received: Salary") })
                Pill("Gift", { vm.receivedFor(t, "Received: Gift") })
                Pill("Other", { vm.receivedFor(t, "Received: Other") })
                Pill("Refund", { onLink(t) })
                Pill("My own transfer", { vm.ownTransfer(t) })
            }
            Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { vm.ignore(t) }) { Text("Ignore", color = muted()) } }
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
    if (borrowPick) PersonPickerDialog(vm, "Who lent you this?", { borrowPick = false }) { vm.borrowed(t, it); borrowPick = false }
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
        Text("Nothing auto-sorted yet. Payments from merchants you've tagged before will show up here.", color = muted(), fontSize = 15.sp)
        return
    }
    Text("Counted as yours automatically. Change any that were for someone else.", fontSize = 13.sp, color = muted())
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
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
        Text("No failed payments. Mark one from its ⋮ menu; it closes when the refund arrives.", color = muted(), fontSize = 15.sp)
        return
    }
    Text("Waiting for refunds. These close when the bank's refund message arrives.", fontSize = 13.sp, color = muted())
    Spacer(Modifier.height(6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
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
fun PendingScreen(vm: MainVm, q: String) {
    val aliases by vm.aliases.collectAsState()
    val allRows by vm.pending.collectAsState()
    val rows = if (q.isBlank()) allRows else allRows.filter { it.person.contains(q.trim(), true) || Repo.nameOnly(it.merchant, aliases).contains(q.trim(), true) || it.category.contains(q.trim(), true) }
    val owe by vm.payables.collectAsState()
    val oweBy = remember(owe) { Logic.owedByYou(owe) }
    var payFor by remember { mutableStateOf<String?>(null) }
    var addOwe by remember { mutableStateOf(false) }
    var addOweFor by remember { mutableStateOf<String?>(null) }
    var settleFor by remember { mutableStateOf<String?>(null) }
    var remindFor by remember { mutableStateOf<String?>(null) }
    val grouped = remember(rows) { rows.groupBy { it.person } }
    val total = remember(rows) { rows.sumOf { it.amount - it.settled } }
    val ordered = remember(grouped) { grouped.entries.sortedByDescending { e -> e.value.sumOf { it.amount - it.settled } }.toList() }
    val ticks = remember(Epoch.value) { mutableMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<Long>>() }

    AppCard {
        Text("Owed to you", fontSize = 13.sp, color = muted())
        Text(Repo.money(total), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (total > 0) teal() else muted())
        Text("${grouped.size} people · ${rows.size} items", fontSize = 13.sp, color = muted())
        val lent = Logic.lentTotal(rows)
        TextButton(onClick = { addOwe = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+ I owe someone") }
        if (lent > 0.004) Text("Cash lent ${Repo.money(lent)} · Shared spends ${Repo.money(total - lent)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = teal())
    }
    Spacer(Modifier.height(12.dp))
    if (rows.isEmpty()) Text("No one owes you right now.", color = muted(), fontSize = 15.sp)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
        if (oweBy.isNotEmpty()) {
            item(key = "owe-head") {
                Text("You owe · ${Repo.money(oweBy.sumOf { it.second })}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            items(oweBy, key = { "owe-" + it.first }) { (person, amt) ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person, 36)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(person, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("You need to pay", fontSize = 12.sp, color = muted())
                        }
                        Text(Repo.money(amt), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = { payFor = person }) { Text("Paid") }
                    }
                }
            }
            item(key = "owe-gap") { Spacer(Modifier.height(4.dp)); Text("Owed to you", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        items(ordered, key = { it.key }) { e ->
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
                // newest first; about three rows show, the rest scroll inside the card
                Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                e.value.sortedByDescending { it.txnDate }.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { if (r.shareId in ticked) ticked.remove(r.shareId) else ticked.add(r.shareId) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.shareId in ticked, onCheckedChange = { if (it) ticked.add(r.shareId) else ticked.remove(r.shareId) })
                        Text("${Repo.shortDate(r.txnDate)} · ${if (r.category.isNotBlank() && r.category != "Other") r.category + " · " else ""}${Repo.nameOnly(r.merchant, aliases)}", Modifier.weight(1f), fontSize = 14.sp)
                        Text(Repo.money(r.amount - r.settled), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { remindFor = e.key }, modifier = Modifier.weight(1f)) { Text(if (ticked.isEmpty()) "Remind" else "Remind · ${ticked.size}", maxLines = 1) }
                    OutlinedButton(onClick = { settleFor = e.key }, modifier = Modifier.weight(2f)) {
                        Text(if (ticked.isEmpty()) "Mark all paid" else "Paid · ${ticked.size} · ${Logic.money(Logic.dueOf(e.value, ticked.toSet()))}", maxLines = 1)
                    }
                }
            }
        }
    }
    payFor?.let { person ->
        val list = owe.filter { it.person == person }
        val due = list.sumOf { it.amount - it.paid }
        var amt by remember(person) { mutableStateOf(due.toString().removeSuffix(".0")) }
        AlertDialog(
            onDismissRequest = { payFor = null },
            title = { Text("Paid to $person") },
            text = { OutlinedTextField(value = amt, onValueChange = { amt = it }, singleLine = true, label = { Text("Amount ₹ (you owe ${Repo.money(due)})") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)) },
            confirmButton = { TextButton(enabled = (amt.replace(",", "").toDoubleOrNull() ?: 0.0) > 0, onClick = { vm.payPerson(list, amt.replace(",", "").toDouble()); payFor = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { payFor = null }) { Text("Cancel") } }
        )
    }
    if (addOwe) PersonPickerDialog(vm, "Who do you owe?", { addOwe = false }) { addOweFor = it; addOwe = false }
    addOweFor?.let { person ->
        var amt by remember(person) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOweFor = null },
            title = { Text("You owe $person") },
            text = { OutlinedTextField(value = amt, onValueChange = { amt = it }, singleLine = true, label = { Text("Amount ₹") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)) },
            confirmButton = { TextButton(enabled = (amt.replace(",", "").toDoubleOrNull() ?: 0.0) > 0, onClick = { vm.addPayable(person, amt.replace(",", "").toDouble()); addOweFor = null }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addOweFor = null }) { Text("Cancel") } }
        )
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
        val chosen = (ticks[person]?.toSet() ?: emptySet())
        // ticked bills only; with nothing ticked, everything that person owes
        val forThem = rows.filter { it.person == person && (chosen.isEmpty() || it.shareId in chosen) }
        ReminderDialog(person, { upi -> Reminder.build(person, forThem, aliases, upi, System.currentTimeMillis()) }, onDismiss = { remindFor = null })
    }
}

// ======================= ALL TRANSACTIONS =======================

@Composable
fun AllScreen(vm: MainVm, q: String) {
    val aliases by vm.aliases.collectAsState()
    val txns by vm.all.collectAsState()
    val shares by vm.shares.collectAsState()
    var f0 by remember { mutableStateOf(TxnFilter()) }
    val f = f0.copy(query = q)
    var sheetFor by remember { mutableStateOf<Txn?>(null) }
    val tags by vm.tags.collectAsState()
    val byTxn = shares.groupBy { it.txnId }
    val now = System.currentTimeMillis()
    val shown = txns.filter { Filters.matches(it, byTxn[it.id] ?: emptyList(), aliases, f, now) }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPill("Type", Filters.types, f.type) { f0 = f0.copy(type = it) }
        FilterPill("Date", Filters.ranges, f.range) { f0 = f0.copy(range = it) }
        FilterPill("Tag", listOf("Any", "Untagged") + tags, if (f.tagState == "Untagged") "Untagged" else f.tag.ifBlank { "Any" }) {
            f0 = when (it) { "Any" -> f0.copy(tagState = "Any", tag = ""); "Untagged" -> f0.copy(tagState = "Untagged", tag = ""); else -> f0.copy(tagState = "Any", tag = it) }
        }
        FilterPill("Status", Filters.statuses, f.status) { f0 = f0.copy(status = it) }
        if (f0.active) Pill("Clear ✕", { f0 = TxnFilter() }, compact = true)
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
            onSelectAll = { picked.clear(); picked.addAll(shown.map { it.id }) },
            onDone = { picked.clear(); selecting = false })
    } else TextButton(onClick = { selecting = true }) { Text("Select") }
    Spacer(Modifier.height(4.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
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
        Pill(if (on) selected else "$title ▾", { open = true }, selected = on, compact = true)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
private fun TxnRow(t: Txn, ss: List<Share>, vm: MainVm, aliases: Map<String, String>, onChange: (Txn) -> Unit) {
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
    AppCard(onClick = if (spendLike && t.status != "REVERSED") ({ onChange(t) }) else null) {
        TxnHeader(t, aliases, incoming) {
            if (spendLike) Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    if (t.status == "TAGGED" || t.status == "INBOX") DropdownMenuItem(text = { Text("Change who / tag") }, onClick = { moreMenu = false; onChange(t) })
                    DropdownMenuItem(text = { Text("Rename merchant") }, onClick = { moreMenu = false; rename = true })
                    if (t.status != "FAILED" && t.status != "REVERSED") DropdownMenuItem(text = { Text("Payment failed") }, onClick = { moreMenu = false; vm.markFailed(t) })
                }
            }
        }
        if (state.isNotEmpty()) Text(state, fontSize = 13.sp, color = if (t.status == "INBOX" || t.status == "FAILED") amber() else muted())
        if (t.note.isNotBlank()) Text(t.note, fontSize = 13.sp, color = muted())
        if (t.status == "REPAID" && t.note.contains("matched automatically")) TextButton(onClick = { vm.undoAutoSettle(t) }) { Text("Wrong match? Undo") }
        if (spendLike) {
            Spacer(Modifier.height(6.dp))
            Pill(if (t.category.isBlank()) "+ Add tag" else t.category, { onChange(t) })
        }
        val live = ss.filter { it.amount > 0.004 }
        if (live.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // same chip look as the Inbox card and the edit page
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                live.forEach { s ->
                    val label = if (s.person == Repo.ME) "Me" else s.person.substringBefore(" ")
                    val st = if (s.person == Repo.ME) "" else if (s.settled >= s.amount - 0.005) " ✓ paid" else " · owes ${Repo.money(s.amount - s.settled)}"
                    Pill("$label ${Repo.money(s.amount)}$st", { onChange(t) })
                }
            }
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

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
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
