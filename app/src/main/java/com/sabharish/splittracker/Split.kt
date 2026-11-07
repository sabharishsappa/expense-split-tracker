package com.sabharish.splittracker

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * New split in two calm steps:
 * 1. one page: what for, how much, tag, who, how to divide (saved groups fill the people in)
 * 2. ask each person to pay, straight on WhatsApp
 */
@Composable
fun NewSplitFlow(vm: MainVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val groups = remember { Groups.decode(Repo.getPref(ctx, "groups", "")) }
    var done by remember { mutableStateOf<Txn?>(null) }
    val d = done
    if (d != null) RequestStep(vm, d, onClose)
    else TagSheet(
        vm, Txn(bank = "CASH", card = "", kind = "UPI_OUT", amount = 0.0, merchant = "", txnDate = System.currentTimeMillis(), smsTime = System.currentTimeMillis(), status = "INBOX"),
        onDismiss = onClose, onSaved = {}, creating = true, groups = groups,
        onCreated = { made, people ->
            Repo.putPref(ctx, "groups", Groups.encode(Groups.upsert(groups, made.merchant, people)))
            done = made
        }
    )
}

/** Step 2: tick the people, then Send. Each person's WhatsApp message opens one after another. */
@Composable
private fun RequestStep(vm: MainVm, t: Txn, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val shares by produceState<List<Share>>(emptyList(), t.id) { value = vm.sharesFor(t.id) }
    val others = shares.filter { it.person != Repo.ME && it.amount > 0.004 }
    val asked = remember { mutableStateListOf<String>() }
    val ticked = remember { mutableStateListOf<String>() }
    val queue = remember { mutableStateListOf<String>() }     // people still to message in this go
    val upi = Repo.getPref(ctx, "upi", "")
    val allTicked = others.isNotEmpty() && others.all { it.person in ticked }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Split saved", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("${t.merchant} · ${Repo.money(t.amount)}", fontSize = 15.sp, color = muted())
                Spacer(Modifier.height(12.dp))
                if (others.isEmpty()) Text("Nobody else is in this split, so there's nothing to ask for.", color = muted())
                else {
                    Text("Ask everyone to pay. When their payment message arrives it's marked paid for you.", fontSize = 14.sp, color = muted())
                    if (upi.isBlank()) Text("Tip: add your UPI ID in Settings so the message has it.", fontSize = 13.sp, color = amber(), modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${others.size} ${if (others.size == 1) "person" else "people"}", Modifier.weight(1f), fontSize = 13.sp, color = muted())
                        androidx.compose.material3.TextButton(onClick = { if (allTicked) ticked.clear() else { ticked.clear(); ticked.addAll(others.map { it.person }) } }) {
                            Text(if (allTicked) "Clear" else "Select all")
                        }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    others.forEach { s ->
                        val on = s.person in ticked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) ticked.remove(s.person) else ticked.add(s.person) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { if (it) ticked.add(s.person) else ticked.remove(s.person) })
                            Avatar(s.person, 36)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(s.person, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(Repo.money(s.amount), fontSize = 14.sp, color = muted())
                            }
                            if (s.person in asked) Text("Asked ✓", color = green(), fontSize = 13.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(52.dp)) { Text("Skip for now") }
                    Button(
                        enabled = ticked.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp),
                        onClick = { queue.clear(); queue.addAll(others.map { it.person }.filter { it in ticked }) }
                    ) { Text(if (ticked.isEmpty()) "Send" else "Send (${ticked.size})") }
                }
            }
        }
    }
    val current = queue.firstOrNull()
    val share = others.firstOrNull { it.person == current }
    if (current != null && share != null) {
        androidx.compose.runtime.key(current) {
            val sent = remember { mutableStateOf(false) }
            ReminderDialog(current, { u -> Reminder.splitRequest(current, t.merchant, t.amount, share.amount, u) },
                onDismiss = {
                    // sent → carry on to the next person; backed out → stop and return to this page unchanged
                    if (sent.value) queue.removeAt(0) else queue.clear()
                },
                onSent = { sent.value = true; if (current !in asked) asked.add(current); ticked.remove(current) })
        }
    }
}
