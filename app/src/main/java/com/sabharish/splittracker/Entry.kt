package com.sabharish.splittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val ExpenseRed = Color(0xFFE5574B)
private val IncomeBlue = Color(0xFF2F7DE1)
private val MoveGrey = Color(0xFF5B6B7B)

/** Picked day (UTC midnight from the date picker) → that day at the current time of day, in the phone's zone. */
internal fun dayToMillis(utcMidnight: Long, time: LocalTime = LocalTime.now()): Long =
    Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * One calm screen for every manual entry: the amount first (keyboard already up), then a category grid,
 * a day, an account and an optional note — Expense, Income or Transfer. Nothing else.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddEntryDialog(vm: MainVm, onDismiss: () -> Unit) {
    val accounts = Accounts.options(vm.accounts.collectAsState().value)
    val recents by vm.recents.collectAsState()
    val insets = LocalScreenInsets.current
    var kind by remember { mutableStateOf("Expense") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(accounts.first { it.key == Accounts.CASH }) }
    var to by remember { mutableStateOf<AccountView?>(null) }
    var day by remember { mutableStateOf(System.currentTimeMillis()) }
    var pickDay by remember { mutableStateOf(false) }
    val people = remember { mutableStateListOf<String>() }
    val amt = Calc.eval(amount) ?: 0.0
    val target = to?.takeIf { it.key != from.key } ?: accounts.firstOrNull { it.key != from.key }
    val accent = when (kind) { "Expense" -> ExpenseRed; "Income" -> IncomeBlue; else -> MoveGrey }
    val ok = amt > 0 && (kind != "Transfer" || target != null)
    val cats = if (kind == "Income") Entries.INCOME_CATS else Categories.all
    val today = remember { System.currentTimeMillis() }
    val dayMs = 24 * 60 * 60_000L
    val isToday = day / dayMs == today / dayMs
    val isYesterday = day / dayMs == (today - dayMs) / dayMs

    FullScreenDialog(onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = muted()) }
                    Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Segmented(listOf("Expense", "Income", "Transfer"), kind, { kind = it; category = "" })
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Amount: big, centred, in the entry's colour. Plain text, so every digit can be deleted.
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("₹", fontSize = 34.sp, color = accent, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() || it in ".,+×÷−-*/" } },
                            singleLine = true, cursorBrush = SolidColor(accent),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, color = accent, textAlign = TextAlign.Start),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(220.dp),
                            decorationBox = { inner -> if (amount.isEmpty()) Text("0", fontSize = 44.sp, color = accent.copy(alpha = 0.3f), fontWeight = FontWeight.SemiBold); inner() }
                        )
                    }

                    // a small calculator: tap an operator, type the next number, the total shows below
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        listOf("+", "−", "×", "÷").forEach { op ->
                            TextButton(onClick = { if (amount.isNotEmpty() && amount.last() !in "+−×÷") amount += op }) { Text(op, fontSize = 22.sp, color = accent) }
                        }
                        TextButton(onClick = { amount = amount.dropLast(1) }) { Text("⌫", fontSize = 18.sp, color = muted()) }
                    }
                    if (Calc.hasOperator(amount) && amt > 0) Text("= ${Logic.money(amt)}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 15.sp, color = muted())

                    if (kind == "Transfer") {
                        Label("From")
                        AccountRow(accounts, from) { from = it }
                        Label("To")
                        AccountRow(accounts.filter { it.key != from.key }, target ?: from) { to = it }
                        Text("Counted in both balances, left out of your spends.", fontSize = 12.sp, color = muted())
                    } else {
                        Label(if (kind == "Income") "Type" else "Category")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            cats.forEach { c ->
                                val on = c == category
                                Box(
                                    Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(if (on) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .let { if (on) it.then(Modifier) else it }
                                        .clickable { category = if (on) "" else c }
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) { Text("${Entries.emoji(c)}  $c", fontSize = 14.sp, color = if (on) accent else MaterialTheme.colorScheme.onSurface, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
                            }
                        }
                        Label(if (kind == "Income") "Received in" else "Paid from")
                        AccountRow(accounts, from) { from = it }
                    }

                    Label("When")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Today", { day = System.currentTimeMillis() }, selected = isToday)
                        Pill("Yesterday", { day = System.currentTimeMillis() - dayMs }, selected = isYesterday)
                        Pill(if (isToday || isYesterday) "Pick a day" else java.text.SimpleDateFormat("d MMM yyyy").format(day), { pickDay = true }, selected = !isToday && !isYesterday)
                    }

                    if (kind != "Transfer") OutlinedTextField(
                        value = note, onValueChange = { note = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (kind == "Income") "From whom? (optional)" else "Note (optional)") }
                    )

                    if (kind == "Expense" && recents.isNotEmpty()) {
                        Label("Split with (optional)")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            recents.take(6).forEach { p -> Pill(p, { if (p in people) people.remove(p) else people.add(p) }, selected = p in people) }
                        }
                        if (people.isNotEmpty() && amt > 0)
                            Text("Split equally: ${Logic.money(amt / (people.size + 1))} each, including you.", fontSize = 12.sp, color = muted())
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    enabled = ok, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
                    onClick = {
                        when (kind) {
                            "Expense" -> vm.addEntry(note, amt, from.key, day, category.ifBlank { "Other" }, people.toList())
                            "Income" -> vm.addIncome(note, amt, from.key, day, category.ifBlank { "Other" })
                            else -> target?.let { vm.addMoved(from, it, amt, day) }
                        }
                        onDismiss()
                    }
                ) { Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (pickDay) {
        val st = rememberDatePickerState(initialSelectedDateMillis = day)
        DatePickerDialog(
            onDismissRequest = { pickDay = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { day = dayToMillis(it) }; pickDay = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDay = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
}

@Composable
private fun Label(text: String) = Text(text, fontSize = 13.sp, color = muted(), fontWeight = FontWeight.Medium)

@Composable
private fun AccountRow(accounts: List<AccountView>, current: AccountView, onPick: (AccountView) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a -> Pill(a.label, { onPick(a) }, selected = a.key == current.key) }
    }
}
