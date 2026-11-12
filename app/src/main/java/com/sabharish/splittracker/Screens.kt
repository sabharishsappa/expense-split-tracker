package com.sabharish.splittracker

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.layout
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
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
            Text(Repo.title(t.merchant, aliases), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Repo.acct(t.bank, t.card)} · ${Repo.shortDate(t.txnDate)} · ${kindLabel(t.kind)}", fontSize = 13.sp, color = muted())
        }
        Text(
            (if (incoming) "+" else "") + Repo.money(t.amount),
            fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = if (incoming) green() else coral()
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
        add("Refunds" to failed.size)
    }
    androidx.compose.material3.TabRow(
        selectedTabIndex = section.coerceAtMost(tabs.size - 1),
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) {
        tabs.forEachIndexed { i, (name, n) ->
            androidx.compose.material3.Tab(selected = section == i, onClick = { onSection(i) },
                text = { Text(if (n > 0) "$name $n" else name, fontSize = 13.sp, maxLines = 1) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (q.isNotBlank() && spends.isEmpty() && money.isEmpty() && auto.isEmpty() && failed.isEmpty())
        Text("Nothing matches \"${q.trim()}\".", color = muted(), fontSize = 16.sp)
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
internal fun SelectRow(t: Txn, aliases: Map<String, String>, checked: Boolean, partner: Txn? = null, onToggle: () -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                if (partner != null) {
                    Text("Own transfer", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("${Repo.acct(t.bank, t.card)} → ${Repo.acct(partner.bank, partner.card)} · ${Repo.shortDate(t.txnDate)}", fontSize = 12.sp, color = muted())
                } else {
                    Text(Repo.title(t.merchant, aliases), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("${Repo.shortDate(t.txnDate)} · ${if (t.category.isBlank()) "no tag" else t.category}", fontSize = 12.sp, color = muted())
                }
            }
            Text(Repo.money(t.amount), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Group edit: choose a tag and/or people in one row, then the single ✓ applies it to every ticked bill. */
@Composable
internal fun BulkBar(vm: MainVm, picked: List<Txn>, onSelectAll: () -> Unit, onDone: () -> Unit, partners: Map<Long, Txn> = emptyMap()) {
    var confirmDelete by remember { mutableStateOf(false) }
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
            Text(if (picked.isEmpty()) "Select bills" else "${picked.size} selected · ${Repo.money(picked.sumOf { it.amount })}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSelectAll) { Text("Select all") }
            if (picked.isNotEmpty()) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
            FilledIconButton(enabled = ready, modifier = Modifier.size(36.dp), onClick = { vm.bulkApply(picked.filter { it.status != "SELF" }, tag, people.toList()); onDone() }) {
                Icon(Icons.Default.Check, contentDescription = "Apply to all")
            }
        }
        if (ready) {
            Spacer(Modifier.height(6.dp))
            Text(Logic.bulkSummary(picked.size, picked.sumOf { it.amount }, tag, people.toList()), fontSize = 12.sp, color = muted())
        }
    }
    if (more) PeopleSelectDialog(vm, "Who were these for?", { more = false }) { chosen -> chosen.forEach { if (it !in people) people.add(it) }; more = false }
    if (confirmDelete) ConfirmDelete(picked.size, { confirmDelete = false }) { confirmDelete = false; vm.deleteAll(picked + picked.mapNotNull { partners[it.id] }); onDone() }
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
        Text("You're all caught up. New payments will show up here.", color = muted(), fontSize = 16.sp)
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
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 96.dp)) {
        items(items, key = { it.id }) { t ->
            if (selecting) SelectRow(t, aliasesNow, t.id in picked) { if (t.id in picked) picked.remove(t.id) else picked.add(t.id) }
            else Box(Modifier.layout { m, c ->
                val w = c.maxWidth + 28.dp.roundToPx()
                val p = m.measure(c.copy(minWidth = w, maxWidth = w))
                layout(c.maxWidth, p.height) { p.place(-14.dp.roundToPx(), 0) }
            }) {
                MmRow(t, emptyList(), aliasesNow, null,
                    onTap = { sheetFor = t to (chosenBy[t.id] ?: emptyList()) },
                    onLong = { selecting = true; picked.clear(); picked.add(t.id) },
                    onConfirm = { vm.confirm(t) })
            }
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
    var refundFor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
                    DropdownMenuItem(text = { Text("Refund received") }, onClick = { moreMenu = false; refundFor = true })
                    DropdownMenuItem(text = { Text("Payment failed") }, onClick = { moreMenu = false; vm.markFailed(t) })
                    if (t.kind == "UPI_OUT") DropdownMenuItem(text = { Text("Own transfer") }, onClick = { moreMenu = false; vm.ownTransfer(t) })
                    DropdownMenuItem(text = { Text("Not a spend (skip)") }, onClick = { moreMenu = false; vm.ignore(t) })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { moreMenu = false; confirmDelete = true })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Pill(if (t.category.isBlank()) "+ Add tag" else t.category, { catMenu = true })
            TagMenu(vm, catMenu, { catMenu = false }) { vm.setCategory(t, it) }
        }
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.height(2.dp))
        fun toggle(p: String) { setSel(if (p in sel) sel - p else sel + p) }
        val s = sug
        val asBefore = if (s != null && s.action == "PEOPLE") s.people.split(",").filter { it.isNotBlank() } else if (s != null && s.action == "MINE") listOf(Repo.ME) else emptyList()
        val chips = (listOf(Repo.ME) + recents.filter { it !in asBefore }.take(3) + sel.filter { it != Repo.ME && it !in recents }).distinct()
        val ordered = Logic.selectedFirst(chips, sel.toList())
        val labelOf = chips.zip(firstNames(chips)).toMap().mapValues { if (it.key == Repo.ME) "Me" else it.value }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.filter { it in sel }.forEach { p -> Pill(labelOf[p] ?: p, { toggle(p) }, selected = true) }
            if (asBefore.isNotEmpty() && sel != asBefore) {
                val label = if (asBefore == listOf(Repo.ME)) "mine" else firstNames(asBefore.filter { it != Repo.ME }).joinToString(" + ").ifEmpty { "You" } +
                    if (asBefore.contains(Repo.ME)) " & you" else ""
                Pill("As before: $label", { setSel(asBefore) })
            }
            ordered.filter { it !in sel }.forEach { p -> Pill(labelOf[p] ?: p, { toggle(p) }) }
            Pill("More…", { onMore(t, sel.toList()) })
        }
        if (groups.isNotEmpty()) {
            // saved groups sit in their own row, so they are not mistaken for people
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.take(4).forEach { g ->
                    val on = g.people.all { it in sel }
                    Pill(g.name, { setSel(if (on) sel - g.people.toSet() else (sel + Repo.ME + g.people).distinct()) }, selected = on)
                }
            }
        }
        if (sel.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                FilledIconButton(onClick = { vm.confirm(t) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Check, contentDescription = "Confirm", modifier = Modifier.size(20.dp)) }
            }
        }
        if (sel.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Logic.selectionSummary(t.amount, sel.toList()), Modifier.weight(1f), fontSize = 16.sp)
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
    if (refundFor) RefundDialog(t, { refundFor = false }) { a -> refundFor = false; vm.refunded(t, a) }
    if (confirmDelete) ConfirmDelete(1, { confirmDelete = false }) { confirmDelete = false; vm.deleteAll(listOf(t)) }
}

@Composable
private fun MoneyInList(vm: MainVm, items: List<Txn>) {
    val pending by vm.pending.collectAsState()
    val recents by vm.recents.collectAsState()
    val contacts by vm.contacts.collectAsState()
    var pickFor by remember { mutableStateOf<Txn?>(null) }
    var linkFor by remember { mutableStateOf<Txn?>(null) }
    if (items.isEmpty()) {
        Text("No money received to review.", color = muted(), fontSize = 16.sp)
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
                Text("${Repo.title(g.merchant, aliases)} · ${Repo.money(g.amount)} · ${fullDate(g.txnDate)}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
        Text("Nothing auto-sorted yet. Payments from merchants you've tagged before will show up here.", color = muted(), fontSize = 16.sp)
        return
    }
    Text("Sorted automatically from what you tagged before. They count once you tick them.", fontSize = 13.sp, color = muted())
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { vm.confirmAll(items) }) { Text("✓ Confirm all ${items.size}") }
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
                    Spacer(Modifier.padding(4.dp))
                    FilledIconButton(onClick = { vm.confirm(t) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Check, contentDescription = "Confirm") }
                }
            }
        }
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
}

@Composable
private fun FailedList(vm: MainVm, items: List<Txn>) {
    val aliases by vm.aliases.collectAsState()
    var refundFor by remember { mutableStateOf<Txn?>(null) }
    refundFor?.let { t -> RefundDialog(t, { refundFor = null }) { a -> refundFor = null; vm.refunded(t, a) } }
    if (items.isEmpty()) {
        Text("No failed payments. Mark one from its ⋮ menu; it closes when the refund arrives.", color = muted(), fontSize = 16.sp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refundFor = t }) { Text("Refund received") }
                    OutlinedButton(onClick = { vm.unfail(t.id) }) { Text("It didn't fail") }
                }
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

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        item(key = "owed-head") {
            Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Owed to you", fontSize = 14.sp); Text(Logic.money(total).removePrefix("₹"), color = blue(), fontSize = 17.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("You owe", fontSize = 14.sp); Text(Logic.money(oweBy.sumOf { it.second }).removePrefix("₹"), color = coral(), fontSize = 17.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("People", fontSize = 14.sp); Text("${grouped.size}", fontSize = 17.sp) }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
        TextButton(onClick = { addOwe = true }) { Text("+ I owe someone", color = coral(), fontSize = 14.sp) }
        if (rows.isEmpty()) Text("No one owes you right now.", color = muted(), fontSize = 16.sp, modifier = Modifier.padding(16.dp))
            }
        }
        if (oweBy.isNotEmpty()) {
            item(key = "owe-head") {
                Text("You owe", fontSize = 14.sp, color = muted(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            items(oweBy, key = { "owe-" + it.first }) { (person, amt) ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person, 36)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(person, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text("You need to pay", fontSize = 12.sp, color = muted())
                        }
                        Text(Repo.money(amt), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = { payFor = person }) { Text("Paid") }
                    }
                }
            }
            item(key = "owe-gap") { Text("Owed to you", fontSize = 14.sp, color = muted(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        }
        items(ordered, key = { it.key }) { e ->
            val owed = e.value.sumOf { it.amount - it.settled }
            val days = daysSince(e.value.minOf { it.txnDate })
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(e.key, fontSize = 17.sp)
                        Text("${e.value.size} items · oldest $days days", fontSize = 13.sp,
                            color = muted())
                    }
                    Text(Logic.money(owed).removePrefix("₹"), fontSize = 17.sp, color = blue())
                }
                Spacer(Modifier.height(8.dp))
                val ticked = ticks.getOrPut(e.key) { mutableStateListOf<Long>() }
                // newest first; three rows show, "Show all" opens the rest (no scrolling inside a scrolling list)
                var expanded by remember(e.key) { mutableStateOf(false) }
                val sorted = remember(e.value) { e.value.sortedByDescending { it.txnDate } }
                Column {
                (if (expanded) sorted else sorted.take(3)).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp).clickable { if (r.shareId in ticked) ticked.remove(r.shareId) else ticked.add(r.shareId) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.shareId in ticked, onCheckedChange = { if (it) ticked.add(r.shareId) else ticked.remove(r.shareId) })
                        Text("${Repo.shortDate(r.txnDate)} · ${if (r.category.isNotBlank() && r.category != "Other") r.category + " · " else ""}${Repo.nameOnly(r.merchant, aliases)}", Modifier.weight(1f), fontSize = 14.sp)
                        Text(Repo.money(r.amount - r.settled), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                    if (sorted.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${sorted.size}") }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { remindFor = e.key }, modifier = Modifier.weight(1f)) { Text(if (ticked.isEmpty()) "Remind" else "Remind · ${ticked.size}", maxLines = 1) }
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
    val filtered = txns.filter { Filters.matches(it, byTxn[it.id] ?: emptyList(), aliases, f, now) }
    val merged = remember(filtered) { Transfers.merge(filtered) }   // an own transfer is one entry, not two
    val shown = merged.shown
    var mode by remember { mutableStateOf("List") }
    Spacer(Modifier.height(6.dp))
    Segmented(listOf("List", "Calendar"), mode, { mode = it })
    if (mode == "Calendar") {
        CalendarPane(vm, txns, byTxn, aliases) { sheetFor = it }
        sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
        return
    }
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
            onDone = { picked.clear(); selecting = false }, partners = merged.partner)
    } else TextButton(onClick = { selecting = true }) { Text("Select") }
    Spacer(Modifier.height(4.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
        items(shown, key = { it.id }) { t ->
            if (selecting) SelectRow(t, aliases, t.id in picked, merged.partner[t.id]) { if (t.id in picked) picked.remove(t.id) else picked.add(t.id) }
            else TxnRow(t, byTxn[t.id] ?: emptyList(), vm, aliases, merged.partner[t.id], onSelect = { selecting = true; picked.clear(); picked.add(t.id) }) { sheetFor = it }
        }
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
}

/** A pill that opens a menu; highlighted when something other than "Any" is chosen. */
@Composable
internal fun FilterPill(title: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
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
private fun TxnRow(t: Txn, ss: List<Share>, vm: MainVm, aliases: Map<String, String>, partner: Txn? = null, onSelect: () -> Unit = {}, onChange: (Txn) -> Unit) {
    var moreMenu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleteItem: @Composable () -> Unit = {
        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { moreMenu = false; confirmDelete = true })
    }
    if (confirmDelete) ConfirmDelete(1, { confirmDelete = false }) { confirmDelete = false; vm.deleteAll(listOfNotNull(t, partner)) }
    if (t.status == "SELF" && partner != null) {
        AppCard(onLongClick = onSelect) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Own transfer", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("${Repo.acct(t.bank, t.card)} → ${Repo.acct(partner.bank, partner.card)} · ${Repo.shortDate(t.txnDate)}", fontSize = 13.sp, color = muted())
                }
                Text(Repo.money(t.amount), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) { deleteItem() }
                }
            }
        }
        return
    }
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
    AppCard(onLongClick = onSelect, onClick = if (spendLike && t.status != "REVERSED") ({ onChange(t) }) else null) {
        TxnHeader(t, aliases, incoming) {
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    if (spendLike) {
                        if (t.status == "TAGGED" || t.status == "INBOX") DropdownMenuItem(text = { Text("Change who / tag") }, onClick = { moreMenu = false; onChange(t) })
                        DropdownMenuItem(text = { Text("Rename merchant") }, onClick = { moreMenu = false; rename = true })
                        if (t.status != "FAILED" && t.status != "REVERSED") DropdownMenuItem(text = { Text("Payment failed") }, onClick = { moreMenu = false; vm.markFailed(t) })
                    }
                    deleteItem()
                }
            }
        }
        if (state.isNotEmpty()) Text(state, fontSize = 13.sp, color = if (t.status == "INBOX" || t.status == "FAILED") amber() else muted())
        if (t.note.isNotBlank()) Text(t.note, fontSize = 13.sp, color = muted())
        if (t.status == "REPAID" && t.note.contains("matched automatically")) TextButton(onClick = { vm.undoAutoSettle(t) }) { Text("Wrong match? Undo") }
        val live = ss.filter { it.amount > 0.004 }
        if (spendLike || live.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // tag and people sit in one compact row; a spend that is all yours just says "Mine"
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (spendLike) Pill(if (t.category.isBlank()) "+ Add tag" else t.category, { onChange(t) }, compact = true)
                if (live.isNotEmpty() && live.all { it.person == Repo.ME }) Pill("Mine", { onChange(t) }, compact = true)
                else live.forEach { s ->
                    val label = if (s.person == Repo.ME) "Me" else s.person.substringBefore(" ")
                    val st = if (s.person == Repo.ME) "" else if (s.settled >= s.amount - 0.005) " ✓ paid" else " · owes ${Repo.money(s.amount - s.settled)}"
                    Pill("$label ${Repo.money(s.amount)}$st", { onChange(t) }, compact = true)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val txns by vm.all.collectAsState()
    val shareList by vm.shares.collectAsState()
    val rows by vm.summary.collectAsState()
    var offset by remember { mutableIntStateOf(0) }
    val today = remember { Calendar.getInstance() }
    val (y, m) = Stats.shift(today.get(Calendar.YEAR), today.get(Calendar.MONTH), offset)
    var budgets by remember { mutableStateOf(Budgets.decode(Repo.getPref(ctx, "budgets", ""))) }
    var editKey by remember { mutableStateOf<String?>(null) }
    var pickTag by remember { mutableStateOf(false) }
    fun saveBudgets(b: Map<String, Double>) { budgets = b; Repo.putPref(ctx, "budgets", Budgets.encode(b)) }

    val shares = remember(shareList) { shareList.groupBy { it.txnId } }
    val spent = remember(txns, shares, y, m) { Stats.spentInMonth(txns, shares, y, m) }
    val income = remember(txns, y, m) { Stats.incomeInMonth(txns, y, m) }
    val byCat = remember(txns, shares, y, m) { Stats.byCategory(txns, shares, y, m) }
    val slices = remember(byCat) { Stats.slices(byCat) }
    val trend = remember(txns, shares, y, m) { Stats.trend(txns, shares, y, m, 6) }
    val label = "${Stats.monthLabel(m)} $y"
    val inMonth = rows.filter { Stats.monthOf(it.txnDate) == (y to m) }
    val forOthers = inMonth.filter { it.person != Repo.ME }.sumOf { it.shareAmt }
    val receivable = rows.filter { it.person != Repo.ME }.sumOf { it.shareAmt - it.settled }.coerceAtLeast(0.0)
    val byCard = inMonth.groupBy { it.card }.entries.sortedByDescending { e -> e.value.sumOf { it.shareAmt } }
    val total = budgets[Budgets.TOTAL]

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset -= 1 }) { Text("‹ Prev") }
                Text(label, fontWeight = FontWeight.Medium, fontSize = 17.sp)
                TextButton(onClick = { if (offset < 0) offset += 1 }, enabled = offset < 0) { Text("Next ›") }
            }
        }
        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Spent", fontSize = 13.sp, color = muted())
                        Text(Repo.money(spent), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Income", fontSize = 13.sp, color = muted())
                        Text(Repo.money(income), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (income > 0) green() else muted())
                    }
                }
                if (income > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("Saved ${Repo.money(income - spent)}", fontSize = 14.sp, color = if (income - spent >= 0) green() else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
                if (forOthers > 0.004 || receivable > 0.004) {
                    Spacer(Modifier.height(6.dp))
                    Text("Others owe you ${Repo.money(receivable)}", fontSize = 13.sp, color = muted())
                }
            }
        }
        item {
            AppCard {
                Text("Budget", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (total == null) {
                    TextButton(onClick = { editKey = Budgets.TOTAL }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Set monthly budget") }
                } else BudgetRow("This month", spent, total, onClick = { editKey = Budgets.TOTAL }, perDay = if (offset == 0) Budgets.perDay(total, spent, Budgets.daysLeftInMonth(System.currentTimeMillis())) else null)
                val catLimits = budgets.filterKeys { it != Budgets.TOTAL }
                catLimits.forEach { (tag, limit) ->
                    Spacer(Modifier.height(10.dp))
                    BudgetRow(tag, byCat.firstOrNull { it.first.equals(tag, true) }?.second ?: 0.0, limit, onClick = { editKey = tag })
                }
                Spacer(Modifier.height(8.dp))
                Box {
                    Pill("+ Budget for a tag", { pickTag = true }, compact = true)
                    TagMenu(vm, pickTag, { pickTag = false }) { editKey = it; pickTag = false }
                }
            }
        }
        if (slices.isNotEmpty()) {
            item {
                AppCard {
                    Text("Where my money went", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    PieChart(slices, Modifier.fillMaxWidth().height(170.dp))
                    Spacer(Modifier.height(10.dp))
                    slices.forEachIndexed { i, sl ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(sliceColor(i), RoundedCornerShape(3.dp)))
                            Text(sl.label, Modifier.weight(1f).padding(start = 8.dp), fontSize = 14.sp)
                            Text("${Math.round(sl.fraction * 100)}%", fontSize = 13.sp, color = muted())
                            Spacer(Modifier.size(10.dp))
                            Text(Repo.money(sl.value), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        item {
            AppCard {
                Text("Last 6 months", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                val top = trend.maxOf { it.second }.coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    trend.forEachIndexed { i, (name, v) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(if (v > 0) Repo.compact(v) else "", fontSize = 12.sp, color = muted(), maxLines = 1)
                            Box(Modifier.fillMaxWidth().height((90 * (v / top)).toFloat().coerceAtLeast(if (v > 0) 4f else 1f).dp)
                                .background(if (i == trend.lastIndex) teal() else teal().copy(alpha = 0.45f), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                            Text(name, fontSize = 12.sp, color = muted())
                        }
                    }
                }
            }
        }
        if (byCard.isNotEmpty()) {
            item {
                AppCard {
                    Text("By card / account", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    byCard.forEach { e ->
                        val mine = e.value.filter { it.person == Repo.ME }.sumOf { it.shareAmt }
                        val o = e.value.filter { it.person != Repo.ME }.sumOf { it.shareAmt }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (e.key.isBlank()) "Cash" else "••${e.key}", fontSize = 14.sp)
                            Text("mine ${Repo.money(mine)} · others ${Repo.money(o)}", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (spent <= 0.0 && income <= 0.0) item { Text("Nothing recorded for this month yet.", color = muted(), fontSize = 16.sp) }
    }
    editKey?.let { key ->
        BudgetDialog(if (key == Budgets.TOTAL) "Monthly budget" else "Budget for $key", budgets[key], { editKey = null }) { v -> saveBudgets(Budgets.set(budgets, key, v)); editKey = null }
    }
}

private val SLICE_COLORS = listOf(Color(0xFFFF6159), Color(0xFF4DA3FF), Color(0xFFFFB84D), Color(0xFF4CC38A), Color(0xFFA78BFA), Color(0xFF8A8D96))
private fun sliceColor(i: Int) = SLICE_COLORS[i % SLICE_COLORS.size]

@Composable
private fun PieChart(slices: List<Stats.Slice>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val d = minOf(size.width, size.height)
        val stroke = d * 0.22f
        val box = androidx.compose.ui.geometry.Size(d - stroke, d - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset((size.width - box.width) / 2, (size.height - box.height) / 2)
        var start = -90f
        slices.forEachIndexed { i, sl ->
            val sweep = sl.fraction * 360f
            drawArc(sliceColor(i), start, (sweep - 1.5f).coerceAtLeast(0.5f), false, topLeft, box,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            start += sweep
        }
    }
}

/** One budget line: name, spent of limit, a bar that turns amber near the limit and red over it. */
@Composable
private fun BudgetRow(name: String, spent: Double, limit: Double, onClick: () -> Unit, perDay: Double? = null) {
    val st = Budgets.state(spent, limit)
    val color = when (st) { Budgets.State.OK -> teal(); Budgets.State.CLOSE -> amber(); Budgets.State.OVER -> MaterialTheme.colorScheme.error }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${Repo.money(spent)} of ${Repo.money(limit)}", fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(Budgets.fraction(spent, limit).coerceAtLeast(0.01f)).height(8.dp).background(color, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (st == Budgets.State.OVER) "Over by ${Repo.money(spent - limit)}" else "${Repo.money(limit - spent)} left" +
                (if (perDay != null) " · safe to spend ${Repo.money(perDay)} a day" else ""),
            fontSize = 12.sp, color = if (st == Budgets.State.OVER) MaterialTheme.colorScheme.error else muted()
        )
    }
}

@Composable
private fun BudgetDialog(title: String, current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.let { it.toString().removeSuffix(".0") } ?: "") }
    val v = text.replace(",", "").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Limit per month ₹") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                Text("Counts your own share of spends this month.", fontSize = 12.sp, color = muted())
            }
        },
        confirmButton = { TextButton(enabled = v != null && v > 0, onClick = { onSave(v) }) { Text("Save") } },
        dismissButton = {
            if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


/** One confirmation for deleting one or many transactions (an own transfer counts as one). */
@Composable
internal fun ConfirmDelete(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete this transaction?" else "Delete $count transactions?") },
        text = { Text("They are removed together with their splits. Use this for duplicates or entries you don't want. They won't come back when messages are read again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/** Month calendar with what you spent (and earned) each day; tap a day to see its transactions. */
@Composable
private fun CalendarPane(vm: MainVm, txns: List<Txn>, byTxn: Map<Long, List<Share>>, aliases: Map<String, String>, onChange: (Txn) -> Unit) {
    val today = remember { Calendar.getInstance() }
    var offset by remember { mutableIntStateOf(0) }
    val (y, m) = Stats.shift(today.get(Calendar.YEAR), today.get(Calendar.MONTH), offset)
    var picked by remember(offset) { mutableStateOf(if (offset == 0) today.get(Calendar.DAY_OF_MONTH) else 1) }
    val totals = remember(txns, byTxn, y, m) { Stats.dayTotals(txns, byTxn, y, m) }
    val weeks = remember(y, m) { Stats.weeks(y, m) }
    val dayTxns = remember(txns, y, m, picked) {
        val c = Calendar.getInstance()
        txns.filter { t -> Stats.inMonth(t, y, m) && c.apply { timeInMillis = t.txnDate }.get(Calendar.DAY_OF_MONTH) == picked }.sortedByDescending { it.txnDate }
    }
    val merged = remember(dayTxns) { Transfers.merge(dayTxns) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset -= 1 }) { Text("‹ Prev") }
                Text("${Stats.monthLabel(m)} $y", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                TextButton(onClick = { if (offset < 0) offset += 1 }, enabled = offset < 0) { Text("Next ›") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), fontSize = 12.sp, color = muted(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { d ->
                            if (d == null) Spacer(Modifier.weight(1f).height(54.dp))
                            else {
                                val tot = totals[d]
                                val on = d == picked
                                val isToday = offset == 0 && d == today.get(Calendar.DAY_OF_MONTH)
                                Column(
                                    Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                        .clickable { picked = d }.padding(top = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(d.toString(), fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal, color = if (isToday) teal() else MaterialTheme.colorScheme.onSurface)
                                    if (tot != null && tot.spent > 0) Text(Repo.compact(tot.spent), fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                                    if (tot != null && tot.income > 0) Text(Repo.compact(tot.income), fontSize = 12.sp, color = green(), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            val t = totals[picked]
            Text("${picked} ${Stats.monthLabel(m)}" + (if (t != null) " · spent ${Repo.money(t.spent)}" + (if (t.income > 0) " · income ${Repo.money(t.income)}" else "") else ""),
                fontWeight = FontWeight.Medium, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            if (merged.shown.isEmpty()) Text("Nothing on this day.", color = muted(), fontSize = 14.sp)
        }
        items(merged.shown, key = { it.id }) { t ->
            TxnRow(t, byTxn[t.id] ?: emptyList(), vm, aliases, merged.partner[t.id], onSelect = {}) { onChange(it) }
        }
    }
}
