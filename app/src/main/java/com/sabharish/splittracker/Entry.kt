package com.sabharish.splittracker

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Add money that sends no SMS: cash, another bank, a wallet — or move money between your own accounts. */
@Composable
fun AddEntryDialog(vm: MainVm, onDismiss: () -> Unit) {
    val accounts = Accounts.options(vm.accounts.collectAsState().value)
    var kind by remember { mutableStateOf("Spent") }
    var amount by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(accounts.first { it.key == Accounts.CASH }) }
    var to by remember { mutableStateOf<AccountView?>(null) }
    val amt = amount.replace(",", "").toDoubleOrNull() ?: 0.0
    val moving = kind == "Moved"
    val target = to?.takeIf { it.key != from.key } ?: accounts.firstOrNull { it.key != from.key }
    val ok = amt > 0 && (!moving || target != null)

    @Composable
    fun picker(label: String, current: AccountView, onPick: (AccountView) -> Unit) {
        Text(label, fontSize = 12.sp, color = muted())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accounts.forEach { a -> Pill(a.label, { onPick(a) }, selected = a.key == current.key) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf("Spent", "Received", "Moved"), kind, { kind = it })
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, singleLine = true, label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                if (!moving) OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(if (kind == "Spent") "What for?" else "From whom?") }
                )
                picker(if (moving) "From" else if (kind == "Spent") "Paid from" else "Received in", from) { from = it }
                if (moving) picker("To", target ?: from) { to = it }
                if (moving) Text("Counted in both balances, left out of your spends.", fontSize = 12.sp, color = muted())
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                when (kind) {
                    "Spent" -> vm.addSpent(title, amt, from.key)
                    "Received" -> vm.addReceived(title, amt, from.key)
                    else -> target?.let { vm.addMoved(from, it, amt) }
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
