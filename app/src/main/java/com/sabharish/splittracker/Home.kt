package com.sabharish.splittracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
