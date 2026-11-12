package com.sabharish.splittracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Look and feel borrowed from Money Manager: charcoal pages, coral accent, blue for money in.
// ---------------------------------------------------------------------------------------------

private fun vec(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

val IconBars: ImageVector by lazy { vec("Bars", "M5,9.2h3V19H5V9.2z M10.6,5h2.8v14h-2.8V5z M16.2,13H19v6h-2.8V13z") }
val IconWallet: ImageVector by lazy {
    vec("Wallet", "M21,18v1c0,1.1 -0.9,2 -2,2H5c-1.11,0 -2,-0.9 -2,-2V5c0,-1.1 0.89,-2 2,-2h14c1.1,0 2,0.9 2,2v1h-9c-1.11,0 -2,0.9 -2,2v8c0,1.1 0.89,2 2,2h9z M12,16h10V8H12V16z M16,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5S16.83,13.5 16,13.5z")
}
val IconMore: ImageVector by lazy {
    vec("More", "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S7.1,10 6,10z M18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S19.1,10 18,10z M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S13.1,10 12,10z")
}
val IconTune: ImageVector by lazy {
    vec("Tune", "M3,17v2h6v-2H3z M3,5v2h10V5H3z M13,21v-2h8v-2h-8v-2h-2v6h2z M7,9v2H3v2h4v2h2V9H7z M21,13v-2H11v2h10z M15,9h2V7h4V5h-4V3h-2v6z")
}
val IconSwap: ImageVector by lazy { vec("Swap", "M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3z M9,3L5,6.99h3V14h2V6.99h3L9,3z") }
val IconChevronL: ImageVector by lazy { vec("ChevL", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z") }
val IconChevronR: ImageVector by lazy { vec("ChevR", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }

private val DAY_FMT = SimpleDateFormat("EEE", Locale.ENGLISH)
private val DATE_FMT = SimpleDateFormat("dd/MM/yyyy (EEE)", Locale.ENGLISH)
private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.ENGLISH)

private val EMOJI = mapOf(
    "Grocery" to "🛒", "Food" to "🍜", "Dine Out" to "🍽️", "Cab" to "🚕", "Travel" to "✈️", "Shopping" to "🛍️",
    "Bills" to "🧾", "Fuel" to "⛽", "Entertainment" to "🎬", "Health" to "🧘", "Lent" to "🤝",
    "Salary" to "💰", "Allowance" to "🤑", "Petty cash" to "💵", "Bonus" to "🏅", "Rent" to "🏠", "Gift" to "🎁"
)

fun categoryLabel(c: String) = (EMOJI[c]?.let { "$it " } ?: "") + c
val INCOME_CATEGORIES = listOf("Salary", "Allowance", "Petty cash", "Bonus", "Rent", "Gift", "Other")

/** Opens on the first of the chosen day at the current time of day, so a back-dated entry sorts sensibly. */
private fun atDay(utcPickedMillis: Long): Long {
    val picked = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcPickedMillis }
    val now = Calendar.getInstance()
    now.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
    return now.timeInMillis
}

// ---------------------------------------------------------------------------------------------
// Add entry: Income / Expense / Transfer, laid out like Money Manager
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(vm: MainVm, onDismiss: () -> Unit) {
    val accounts = Accounts.options(vm.accounts.collectAsState().value)
    val tags by vm.tags.collectAsState()
    var type by remember { mutableStateOf("Expense") }
    var at by remember { mutableStateOf(System.currentTimeMillis()) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(accounts.first { it.key == Accounts.CASH }) }
    var to by remember { mutableStateOf<AccountView?>(null) }
    var note by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf("") }   // "", "category", "account", "from", "to"
    var pickDate by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf(false) }
    val amt = amount.replace(",", "").toDoubleOrNull() ?: 0.0
    val target = to?.takeIf { it.key != from.key } ?: accounts.firstOrNull { it.key != from.key }
    val ok = amt > 0 && (type != "Transfer" || target != null)
    val accent = when (type) { "Income" -> blue(); "Expense" -> coral(); else -> MaterialTheme.colorScheme.onSurface }

    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = at)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { at = atDay(it) }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
    if (newTag) NewTagDialog({ newTag = false }) { n -> vm.addTag(n); category = n; newTag = false; panel = "" }

    FullScreenDialog(onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(LocalScreenInsets.current)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        Text(type, fontSize = 20.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Income" to blue(), "Expense" to coral(), "Transfer" to MaterialTheme.colorScheme.onSurface).forEach { (name, c) ->
                            val on = type == name
                            Box(
                                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (isDarkNow()) Color(0xFF17181B) else MaterialTheme.colorScheme.surfaceVariant)
                                    .border(BorderStroke(if (on) 1.5.dp else 1.dp, if (on) c else MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
                                    .clickable { type = name; category = ""; panel = "" },
                                contentAlignment = Alignment.Center
                            ) { Text(name, fontSize = 16.sp, color = if (on) c else muted()) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    FormRow("Date") {
                        Row(Modifier.fillMaxWidth().clickable { pickDate = true }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(DATE_FMT.format(Date(at)), fontSize = 16.sp)
                            Spacer(Modifier.width(16.dp))
                            Text(TIME_FMT.format(Date(at)), fontSize = 17.sp)
                        }
                    }
                    FormRow("Amount") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("₹ ", fontSize = 20.sp)
                            BasicTextField(
                                value = amount, onValueChange = { v -> if (v.all { it.isDigit() || it == '.' || it == ',' }) amount = v },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(accent), modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (type == "Transfer") {
                        FormRow("From", trailing = {
                            IconButton(onClick = { val f = from; from = target ?: f; to = f }) { Icon(IconSwap, contentDescription = "Swap", tint = muted()) }
                        }) { PickerValue(from.label) { panel = "from" } }
                        FormRow("To") { PickerValue(target?.label ?: "", placeholder = "Choose account") { panel = "to" } }
                    } else {
                        FormRow("Category", underline = if (panel == "category") accent else null) {
                            PickerValue(if (category.isBlank()) "" else categoryLabel(category), placeholder = "") { panel = "category" }
                        }
                        FormRow("Account") { PickerValue(from.label) { panel = "account" } }
                    }
                    FormRow("Note") {
                        BasicTextField(
                            value = note, onValueChange = { note = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(accent), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    if (type != "Transfer") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            if (title.isEmpty()) Text(if (type == "Expense") "Where or what for?" else "From whom?", color = muted(), fontSize = 17.sp, modifier = Modifier.padding(vertical = 12.dp))
                            BasicTextField(
                                value = title, onValueChange = { title = it }, singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(accent), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            )
                        }
                        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.height(16.dp))
                    }
                    Box(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (ok) accent else accent.copy(alpha = 0.35f))
                            .clickable(enabled = ok) {
                                when (type) {
                                    "Expense" -> vm.addHandExpense(title, amt, from.key, category.ifBlank { "Other" }, note, at)
                                    "Income" -> vm.addHandIncome(title.ifBlank { note }, amt, from.key, category.ifBlank { "Other" }, at)
                                    else -> target?.let { vm.addMoved(from, it, amt, at, note) }
                                }
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) { Text("Save", fontSize = 17.sp, color = if (type == "Transfer") Color(0xFF17181B) else Color.White) }
                    if (type == "Transfer") Text("Counted in both balances, left out of your spends.", fontSize = 12.sp, color = muted(), modifier = Modifier.padding(20.dp))
                    Spacer(Modifier.height(380.dp))
                }
                when (panel) {
                    "category" -> {
                        val list = if (type == "Income") INCOME_CATEGORIES else (tags.filter { it != "Other" } + "Other")
                        PickerPanel("Category", { panel = "" }, onEdit = { newTag = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
                            Grid3(list.map { categoryLabel(it) }) { i -> category = list[i]; panel = "" }
                        }
                    }
                    "account", "from", "to" -> PickerPanel(if (panel == "to") "To" else if (type == "Transfer") "From" else "Account", { panel = "" }, modifier = Modifier.align(Alignment.BottomCenter)) {
                        Grid3(accounts.map { it.label }) { i -> if (panel == "to") to = accounts[i] else from = accounts[i]; panel = "" }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormRow(label: String, underline: Color? = null, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = muted(), fontSize = 16.sp, modifier = Modifier.width(96.dp))
        Column(Modifier.weight(1f)) {
            content()
            Box(Modifier.fillMaxWidth().height(if (underline != null) 2.dp else 1.dp).background(underline ?: MaterialTheme.colorScheme.outline))
        }
        trailing()
    }
}

@Composable
private fun PickerValue(value: String, placeholder: String = "", onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp).height(24.dp)) {
        if (value.isEmpty()) Text(placeholder, color = muted(), fontSize = 16.sp) else Text(value, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickerPanel(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(if (isDarkNow()) Color(0xFF7B7D86) else Color(0xFFB9BBC2)).padding(start = 18.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 20.sp, color = Color(0xFF17181B), modifier = Modifier.weight(1f))
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Default.Add, contentDescription = "New", tint = Color(0xFF17181B)) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF17181B)) }
        }
        Box(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) { content() }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun Grid3(items: List<String>, onPick: (Int) -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth()) {
        items.chunked(3).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                row.forEachIndexed { c, s ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().border(BorderStroke(0.5.dp, line)).background(MaterialTheme.colorScheme.surface)
                            .clickable { onPick(r * 3 + c) }.padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(s, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f).fillMaxSize().border(BorderStroke(0.5.dp, line)).background(MaterialTheme.colorScheme.surface)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Transactions page: month bar, Daily / Calendar / Monthly / Total / Note, plus our Inbox
// ---------------------------------------------------------------------------------------------

@Composable
fun MonthBar(label: String, onPrev: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean, arrows: Boolean = true, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (arrows) IconButton(onClick = onPrev) { Icon(IconChevronL, contentDescription = "Previous month") } else Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 17.sp)
        if (arrows) IconButton(onClick = onNext, enabled = nextEnabled) { Icon(IconChevronR, contentDescription = "Next month", tint = if (nextEnabled) MaterialTheme.colorScheme.onSurface else muted().copy(alpha = 0.3f)) }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun MmTabs(titles: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        titles.forEachIndexed { i, t ->
            val on = i == selected
            Column(Modifier.clickable { onPick(i) }.padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                Text(t, fontSize = 16.sp, color = if (on) MaterialTheme.colorScheme.onSurface else muted(), fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.height(3.dp).width(if (on) 36.dp else 0.dp).background(coral()))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

@Composable
fun SummaryStrip(income: Double, expense: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        listOf(Triple("Income", income, blue()), Triple("Expenses", expense, coral()), Triple("Total", income - expense, MaterialTheme.colorScheme.onSurface)).forEach { (n, v, c) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(n, fontSize = 14.sp)
                Text(Logic.money(v).removePrefix("₹"), fontSize = 17.sp, color = c, maxLines = 1)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

private fun dayOf(ms: Long, c: Calendar = Calendar.getInstance()): Int = c.apply { timeInMillis = ms }.get(Calendar.DAY_OF_MONTH)

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DailyPane(vm: MainVm, y: Int, m: Int, query: String, flt: TxnFilter, modifier: Modifier = Modifier, onOpenDrafts: () -> Unit = {}) {
    val aliases by vm.aliases.collectAsState()
    val txns by vm.all.collectAsState()
    val shares by vm.shares.collectAsState()
    val byTxn = remember(shares) { shares.groupBy { it.txnId } }
    val now = System.currentTimeMillis()
    val f = flt.copy(query = query)
    val inMonth = remember(txns, byTxn, aliases, f, y, m) {
        txns.filter { Stats.inMonth(it, y, m) && Filters.matches(it, byTxn[it.id] ?: emptyList(), aliases, f, now) }
    }
    val merged = remember(inMonth) { Transfers.merge(inMonth) }
    val days = remember(merged) { merged.shown.groupBy { dayOf(it.txnDate) }.toSortedMap(compareByDescending { it }) }
    val totals = remember(txns, byTxn, y, m) { Stats.dayTotals(txns, byTxn, y, m) }
    var sheetFor by remember { mutableStateOf<Txn?>(null) }
    var actions by remember { mutableStateOf<Txn?>(null) }
    var delFor by remember { mutableStateOf<Txn?>(null) }
    val picked = remember(Epoch.value) { mutableStateListOf<Long>() }
    var selecting by remember(Epoch.value) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting) { picked.clear(); selecting = false }
    if (merged.shown.isEmpty()) { EmptyState("No data available."); return }
    Column(modifier) {
        if (selecting) BulkBar(vm, merged.shown.filter { it.id in picked },
            onSelectAll = { picked.clear(); picked.addAll(merged.shown.map { it.id }) },
            onDone = { picked.clear(); selecting = false }, partners = merged.partner)
        val drafts = merged.shown.filter { Drafts.isDraft(it) }
        if (drafts.isNotEmpty()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${drafts.size} to confirm", Modifier.weight(1f).clickable(onClick = onOpenDrafts), fontSize = 13.sp, color = muted())
            TextButton(onClick = { vm.confirmAll(drafts) }) { Text("Confirm all", color = coral(), fontSize = 13.sp) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            days.forEach { (d, list) ->
                item(key = "h$d") { DayHeader(d, y, m, totals[d]) }
                items(list, key = { it.id }) { t ->
                    if (selecting) SelectRow(t, aliases, t.id in picked, merged.partner[t.id]) { if (t.id in picked) picked.remove(t.id) else picked.add(t.id) }
                    else {
                        val draft = Drafts.isDraft(t)
                        val st = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                            if (v == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd && draft) vm.confirm(t)
                            else if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) delFor = t
                            false
                        })
                        androidx.compose.material3.SwipeToDismissBox(
                            state = st, enableDismissFromStartToEnd = draft,
                            backgroundContent = {
                                val right = st.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
                                Box(Modifier.fillMaxSize().background(if (right) MaterialTheme.colorScheme.error else blue()).padding(horizontal = 24.dp),
                                    contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
                                    Icon(if (right) Icons.Default.Delete else Icons.Default.Check, contentDescription = null, tint = Color.White)
                                }
                            }
                        ) {
                            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                                MmRow(t, byTxn[t.id] ?: emptyList(), aliases, merged.partner[t.id],
                                    onTap = { if (isSpendLike(t) && t.status != "REVERSED") sheetFor = t else actions = t },
                                    onLong = { selecting = true; picked.clear(); picked.add(t.id) },
                                    onConfirm = if (draft) ({ vm.confirm(t) }) else null)
                            }
                        }
                    }
                }
            }
        }
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
    actions?.let { t -> TxnActions(vm, t, merged.partner[t.id], aliases) { actions = null } }
    delFor?.let { t -> ConfirmDelete(1, { delFor = null }) { delFor = null; vm.deleteAll(listOfNotNull(t, merged.partner[t.id])) } }
}

/** Simple payment mode shown on rows; the exact bank and card are one tap away. */
private fun payMode(t: Txn): String = when {
    t.bank.equals("CASH", true) || t.bank.isBlank() -> "Cash"
    t.kind == "UPI_OUT" || t.kind == "UPI_IN" || t.bank == "UPI" -> "UPI"
    t.card.isNotEmpty() && t.card.all { it.isDigit() } && t.kind == "SPEND" -> "Card"
    else -> "Bank"
}

private fun isSpendLike(t: Txn) = t.kind == "SPEND" || t.kind == "UPI_OUT"

@Composable
fun EmptyState(text: String) {
    Column(Modifier.fillMaxSize().padding(bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🐈", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = muted(), fontSize = 16.sp)
    }
}

@Composable
private fun DayHeader(d: Int, y: Int, m: Int, tot: Stats.DayTotal?) {
    val c = Calendar.getInstance().apply { set(y, m, d) }
    val dow = c.get(Calendar.DAY_OF_WEEK)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$d", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(if (dow == Calendar.SUNDAY) coral().copy(alpha = 0.25f) else if (dow == Calendar.SATURDAY) blue().copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(DAY_FMT.format(c.time), fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("${m + 1}.$y", fontSize = 12.sp, color = muted())
        Spacer(Modifier.weight(1f))
        Text(if (tot != null && tot.income > 0) Logic.money(tot.income).removePrefix("₹") else "", color = blue(), fontSize = 14.sp, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
        Text(if (tot != null && tot.spent > 0) Logic.money(tot.spent).removePrefix("₹") else "", color = coral(), fontSize = 14.sp, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MmRow(t: Txn, ss: List<Share>, aliases: Map<String, String>, partner: Txn?, onTap: () -> Unit, onLong: () -> Unit, onConfirm: (() -> Unit)? = null) {
    val own = t.status == "SELF" && partner != null
    val incoming = t.kind == "UPI_IN" || t.kind == "REFUND"
    val state = when (t.status) {
        "INBOX" -> ""; "IGNORED" -> "Skipped"; "REVERSED" -> "Reversed"; "FAILED" -> "Failed · waiting for refund"
        "REPAID" -> "Repayment"; "BILLPAY" -> "Card bill payment"; else -> ""
    }
    val live = ss.filter { it.amount > 0.004 && it.person != Repo.ME }
    val owes = live.sumOf { it.amount - it.settled }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (own) "Transfer" else if (t.category.isBlank()) (if (incoming) "Income" else "—") else t.category,
            color = muted(), fontSize = 13.sp, modifier = Modifier.width(84.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(if (own) "${payMode(t)} → ${payMode(partner!!)}" else Repo.title(t.merchant, aliases), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(payMode(t) + (if (owes > 0.004) " · ${Logic.money(owes).removePrefix("₹")} owed" else ""), fontSize = 12.sp, color = muted())
            if (state.isNotEmpty()) Text(state, fontSize = 12.sp, color = muted())
            
        }
        val draft = Drafts.isDraft(t)
        // fixed slot so every row keeps the same height and the amounts line up
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            if (onConfirm != null) Box(Modifier.size(24.dp).clip(CircleShape).border(BorderStroke(1.5.dp, coral()), CircleShape).clickable(onClick = onConfirm), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = "Confirm", tint = coral(), modifier = Modifier.size(15.dp))
            }
        }
        Text(
            Logic.money(t.amount).removePrefix("₹"), fontSize = 16.sp, maxLines = 1, textAlign = TextAlign.End,
            color = if (draft) muted() else if (own) MaterialTheme.colorScheme.onSurface else if (incoming) blue() else coral()
        )
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
}

/** What you can do with a row that is not a plain spend: rename, mark failed, delete. */
@Composable
private fun TxnActions(vm: MainVm, t: Txn, partner: Txn?, aliases: Map<String, String>, onDone: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (rename) AliasDialog(t.merchant, aliases[Repo.aliasKey(t.merchant).removePrefix("alias|")] ?: "", { rename = false; onDone() }) { vm.setAlias(t.merchant, it); rename = false; onDone() }
    else if (confirm) ConfirmDelete(1, { confirm = false; onDone() }) { confirm = false; vm.deleteAll(listOfNotNull(t, partner)); onDone() }
    else AlertDialog(
        onDismissRequest = onDone,
        title = { Text(Repo.title(t.merchant, aliases), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("${Repo.acct(t.bank, t.card)} · ${fullDate(t.txnDate)} · ${Logic.money(t.amount)}", fontSize = 14.sp, color = muted())
                if (t.note.isNotBlank()) Text(t.note, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                if (t.status == "REPAID" && t.note.contains("matched automatically")) TextButton(onClick = { vm.undoAutoSettle(t); onDone() }) { Text("Wrong match? Undo") }
            }
        },
        confirmButton = { TextButton(onClick = { confirm = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Row { TextButton(onClick = { rename = true }) { Text("Rename") }; TextButton(onClick = onDone) { Text("Close") } } }
    )
}

@Composable
fun CalendarMm(vm: MainVm, y: Int, m: Int, today: Calendar, modifier: Modifier = Modifier) {
    val txns by vm.all.collectAsState()
    val shares by vm.shares.collectAsState()
    val aliases by vm.aliases.collectAsState()
    val byTxn = remember(shares) { shares.groupBy { it.txnId } }
    val totals = remember(txns, byTxn, y, m) { Stats.dayTotals(txns, byTxn, y, m) }
    val weeks = remember(y, m) { Stats.weeksSunday(y, m) }
    var picked by remember(y, m) { mutableStateOf<Int?>(null) }
    var sheetFor by remember { mutableStateOf<Txn?>(null) }
    val line = MaterialTheme.colorScheme.outline
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, s ->
                Text(s, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp, color = if (i == 0) coral() else if (i == 6) blue() else muted())
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            weeks.forEach { week ->
                Row(Modifier.weight(1f).fillMaxWidth().border(BorderStroke(0.5.dp, line))) {
                    week.forEachIndexed { i, d ->
                        Column(Modifier.weight(1f).fillMaxSize().border(BorderStroke(0.25.dp, line)).clickable(enabled = d != null) { picked = d }.padding(2.dp)) {
                            if (d != null) {
                                val isToday = today.get(Calendar.YEAR) == y && today.get(Calendar.MONTH) == m && today.get(Calendar.DAY_OF_MONTH) == d
                                Text(
                                    d.toString(), fontSize = 12.sp,
                                    color = if (isToday) Color(0xFF17181B) else if (i == 0) coral() else if (i == 6) blue() else MaterialTheme.colorScheme.onSurface,
                                    modifier = if (isToday) Modifier.background(Color.White).padding(horizontal = 3.dp) else Modifier
                                )
                                val tot = totals[d]
                                Spacer(Modifier.weight(1f))
                                if (tot != null && tot.income > 0) Text(Repo.compact(tot.income), fontSize = 12.sp, color = blue(), maxLines = 1, modifier = Modifier.align(Alignment.End))
                                if (tot != null && tot.spent > 0) Text(Repo.compact(tot.spent), fontSize = 12.sp, color = coral(), maxLines = 1, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
            }
        }
    }
    picked?.let { d ->
        val day = txns.filter { Stats.inMonth(it, y, m) && dayOf(it.txnDate) == d }.sortedByDescending { it.txnDate }
        val merged = Transfers.merge(day)
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("$d ${Stats.monthLabel(m)} $y") },
            text = {
                if (merged.shown.isEmpty()) Text("Nothing on this day.", color = muted())
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    merged.shown.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { if (isSpendLike(t)) { sheetFor = t; picked = null } }.padding(vertical = 8.dp)) {
                            Text(Repo.title(t.merchant, aliases), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Logic.money(t.amount), color = if (t.kind == "UPI_IN" || t.kind == "REFUND") blue() else coral())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Close") } }
        )
    }
    sheetFor?.let { t -> TagSheet(vm, t, onDismiss = { sheetFor = null }, onSaved = {}) }
}

@Composable
fun MonthlyPane(vm: MainVm, year: Int, onPick: (Int) -> Unit) {
    val txns by vm.all.collectAsState()
    val shares by vm.shares.collectAsState()
    val byTxn = remember(shares) { shares.groupBy { it.txnId } }
    val cal = Calendar.getInstance()
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items((11 downTo 0).toList()) { mo ->
            val inc = Stats.incomeInMonth(txns, year, mo); val exp = Stats.spentInMonth(txns, byTxn, year, mo)
            val future = year > cal.get(Calendar.YEAR) || (year == cal.get(Calendar.YEAR) && mo > cal.get(Calendar.MONTH))
            if (!future) {
                Row(Modifier.fillMaxWidth().clickable { onPick(mo) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${Stats.monthLabel(mo)}", fontSize = 17.sp)
                        Text("1.${mo + 1} ~ ${Calendar.getInstance().apply { set(year, mo, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)}.${mo + 1}", fontSize = 12.sp, color = muted())
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Logic.money(inc).removePrefix("₹"), color = blue(), fontSize = 16.sp)
                        Text(Logic.money(exp).removePrefix("₹"), color = coral(), fontSize = 16.sp)
                    }
                    Text(Logic.money(inc - exp).removePrefix("₹"), fontSize = 16.sp, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

@Composable
fun NotePane(vm: MainVm, y: Int, m: Int) {
    val txns by vm.all.collectAsState()
    val aliases by vm.aliases.collectAsState()
    val system = listOf("Added by hand", "Transfer between your own accounts")
    val rows = remember(txns, y, m) { txns.filter { Stats.inMonth(it, y, m) && it.note.isNotBlank() && it.note !in system }.sortedByDescending { it.txnDate } }
    if (rows.isEmpty()) { EmptyState("No data available."); return }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(rows, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.note.removePrefix("Received: "), fontSize = 16.sp)
                    Text("${Repo.title(t.merchant, aliases)} · ${Repo.shortDate(t.txnDate)}", fontSize = 12.sp, color = muted())
                }
                Text(Logic.money(t.amount).removePrefix("₹"), color = if (t.kind == "UPI_IN" || t.kind == "REFUND") blue() else coral())
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
        }
    }
}

@Composable
fun RoundAddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(60.dp).clip(CircleShape).background(coral()).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun SearchIconButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(if (active) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search") }
}

// ---------------------------------------------------------------------------------------------
// Accounts page: Assets / Liabilities / Total and grouped balances (Money Manager), with Owed inside
// ---------------------------------------------------------------------------------------------

@Composable
fun AccountsPage(vm: MainVm, query: String, section: Int, onSection: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val accounts by vm.accounts.collectAsState()
        val pending by vm.pending.collectAsState()
        val payables by vm.payables.collectAsState()
        val owedMe = remember(pending) {
            pending.filter { it.person != Repo.ME }.groupBy { it.person }.mapValues { e -> e.value.sumOf { it.amount - it.settled } }.filterValues { it > 0.004 }
        }
        val iOwe = remember(payables) { Logic.owedByYou(payables) }
        val withBalance = accounts.filter { it.hasBalance }
        val positive = withBalance.filter { it.balance > 0 }.sumOf { it.balance }
        val negative = withBalance.filter { it.balance < 0 }.sumOf { -it.balance }
        val assets = positive + owedMe.values.sum()
        val liabilities = negative + iOwe.sumOf { it.second }
        var editing by remember { mutableStateOf<AccountView?>(null) }
        var checking by remember { mutableStateOf(false) }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Assets", fontSize = 14.sp); Text(Logic.money(assets).removePrefix("₹"), color = blue(), fontSize = 17.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Liabilities", fontSize = 14.sp); Text(Logic.money(liabilities).removePrefix("₹"), color = coral(), fontSize = 17.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Total", fontSize = 14.sp); Text(Logic.money(assets - liabilities).removePrefix("₹"), fontSize = 17.sp) }
                }
            }
            item { GroupHeader("Accounts", if (withBalance.isEmpty()) "" else Logic.money(withBalance.sumOf { it.balance })) }
            val cash = Accounts.options(accounts).filter { it.key == Accounts.CASH && it !in accounts }
            items(accounts + cash, key = { it.key }) { a ->
                AccountLine(a.label, if (a.hasBalance) Logic.money(a.balance) else "Tap to set balance", a.hasBalance, a.balance < 0) { editing = a }
            }
            item { GroupHeader("Receivables", if (owedMe.isEmpty()) "" else Logic.money(owedMe.values.sum())) }
            if (owedMe.isEmpty()) item { Text("Nobody owes you right now 🎉", color = muted(), fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            items(owedMe.entries.sortedByDescending { it.value }.toList(), key = { "r" + it.key }) { e ->
                AccountLine(e.key, Logic.money(e.value), true, false) { onSection(1) }
            }
            if (iOwe.isNotEmpty()) {
                item { GroupHeader("Payables", Logic.money(iOwe.sumOf { it.second })) }
                items(iOwe, key = { "p" + it.first }) { (p, amt) -> AccountLine(p, Logic.money(amt), true, true) { onSection(1) } }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp)).clickable { checking = true }.padding(16.dp)) {
                    Column {
                        Text("Check a statement", fontSize = 16.sp)
                        Text("Upload a bank or card statement to find missing or extra payments", fontSize = 12.sp, color = muted())
                    }
                }
            }
        }
        editing?.let { a -> BalanceDialog(a, { editing = null }) { n, b -> vm.setBalance(a.key, n, b); editing = null } }
        if (checking) StatementFlow(vm) { checking = false }
    }
}

@Composable
private fun GroupHeader(title: String, total: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = muted(), fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(total, color = blue(), fontSize = 16.sp)
    }
}

@Composable
private fun AccountLine(name: String, value: String, hasValue: Boolean, negative: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = if (hasValue) 17.sp else 13.sp, color = if (!hasValue) muted() else if (negative) coral() else blue())
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
}

// ---------------------------------------------------------------------------------------------
// More: Configuration, Backup, Style, Statement check, Help, Feedback
// ---------------------------------------------------------------------------------------------

@Composable
fun MorePage(vm: MainVm, openSettings: () -> Unit, openStatement: () -> Unit) {
    var page by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text("Paisa Log", fontSize = 14.sp, color = muted())
        }
        val tiles = listOf(
            Triple("Configuration", IconTune, "cfg"),
            Triple("Backup", IconSwap, "backup"),
            Triple("Style", IconBars, "style"),
            Triple("Statement", IconWallet, "stmt"),
            Triple("Help", IconMore, "help"),
            Triple("Privacy", IconChevronR, "privacy")
        )
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                row.forEach { (label, icon, id) ->
                    Column(Modifier.weight(1f).clickable { when (id) { "cfg" -> openSettings(); "stmt" -> openStatement(); else -> page = id } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp).padding(6.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(label, fontSize = 16.sp)
                    }
                }
            }
        }
    }
    when (page) {
        "style" -> StylePage { page = "" }
        "backup" -> BackupPage(vm) { page = "" }
        "help" -> InfoPage("Help", HELP_TEXT) { page = "" }
        "privacy" -> InfoPage("Privacy", "This app has no internet permission. Your messages, people and amounts stay on this phone. They only leave if you choose to: a backup file you save, or a reminder you send yourself.\n\nThere is no account, no cloud sync and no ads. Because nothing is synced, keep a backup: More ▸ Backup ▸ Save a backup file.") { page = "" }
    }
}

private const val HELP_TEXT = "Trans.  Your money record. Daily, Calendar, Monthly, Total and Note views, like Money Manager. The Drafts tab holds payments read from messages. Tick one to confirm it; until then it is not counted.\n\n" +
    "Stats  Where your money goes, budgets and the six-month trend.\n\n" +
    "Accounts  Balances, who owes you, who you owe, and a statement check.\n\n" +
    "+ button  Add an income, expense or transfer by hand. Cash and other-bank spends need this because no message is sent for them.\n\n" +
    "Tip  Tap a row to change its tag or split it with people. Press and hold to select several."

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    FullScreenDialog(onBack) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(LocalScreenInsets.current)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Text(title, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                content()
            }
        }
    }
}

@Composable
private fun InfoPage(title: String, text: String, onBack: () -> Unit) = SubPage(title, onBack) {
    Text(text, fontSize = 16.sp, modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()))
}

@Composable
private fun StylePage(onBack: () -> Unit) = SubPage("Style", onBack) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column {
        listOf("system" to "System Mode", "dark" to "Dark Mode", "light" to "Light Mode").forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { ThemeState.mode = id; Repo.putPref(ctx, "theme", id) }.padding(horizontal = 20.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(24.dp).clip(CircleShape).border(BorderStroke(2.dp, if (ThemeState.mode == id) coral() else MaterialTheme.colorScheme.outline), CircleShape), contentAlignment = Alignment.Center) {
                    if (ThemeState.mode == id) Box(Modifier.size(12.dp).clip(CircleShape).background(coral()))
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
        }
    }
}

@Composable
private fun BackupPage(vm: MainVm, onBack: () -> Unit) = SubPage("Backup", onBack) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val last by vm.lastBackup.collectAsState()
    var note by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            note = try {
                val text = vm.backupText()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                "Backup saved · " + (Backup.describe(text) ?: "")
            } catch (e: Exception) { CrashLog.record(ctx, e); "Couldn't save the backup file" }
        }
    }
    val openFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { confirmRestore = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: "" } }
            catch (e: Exception) { CrashLog.record(ctx, e); note = "Couldn't read that file" }
        }
    }
    confirmRestore?.let { text ->
        val info = remember(text) { Backup.describe(text) }
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text(if (info == null) "This file is not a Paisa Log backup." else "$info\n\nEverything on this phone is replaced by it.") },
            confirmButton = { TextButton(enabled = info != null, onClick = { vm.restore(text); confirmRestore = null; onBack() }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } }
        )
    }
    Column {
        Text(if (last > 0) "Last backup: ${Repo.shortDate(last)}" else "No backup yet", color = muted(), fontSize = 14.sp, modifier = Modifier.padding(20.dp))
        listOf(
            "Backup to a file on this phone" to { saveFile.launch("PaisaLog-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())}.json") },
            "Restore from a backup file" to { openFile.launch(arrayOf("*/*")) }
        ).forEach { (label, act) ->
            Text(label, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable { act() }.padding(horizontal = 20.dp, vertical = 20.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
        }
        if (note.isNotEmpty()) Text(note, color = teal(), fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        Text("Nothing is uploaded. The backup stays in the place you choose.", color = muted(), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}
