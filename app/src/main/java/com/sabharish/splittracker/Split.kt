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
 * New split, in three calm steps:
 * 1. what for + how much (pick a saved group to fill the people in)
 * 2. who and how to divide (the same page used everywhere else)
 * 3. ask each person to pay, straight on WhatsApp
 */
@Composable
fun NewSplitFlow(vm: MainVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var groupPeople by remember { mutableStateOf<List<String>>(emptyList()) }
    var txn by remember { mutableStateOf<Txn?>(null) }       // set once step 1 is done
    var saved by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Txn?>(null) }      // set once step 2 saved
    val groups = remember { Groups.decode(Repo.getPref(ctx, "groups", "")) }

    val t = txn
    val d = done
    when {
        d != null -> RequestStep(vm, d, onClose)
        t != null -> TagSheet(vm, t, initial = if (groupPeople.isEmpty()) emptyList() else listOf(Repo.ME) + groupPeople,
            onDismiss = { if (!saved) { scope.launch { Repo.deleteManual(ctx, t.id) }; txn = null } },
            onSaved = {
                saved = true
                scope.launch {
                    val people = vm.sharesFor(t.id).map { it.person }
                    Repo.putPref(ctx, "groups", Groups.encode(Groups.upsert(groups, title.ifBlank { t.merchant }, people)))
                    done = t.copy(merchant = t.merchant)
                }
            })
        else -> Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                        Text("New split", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
                            label = { Text("What for?") }, placeholder = { Text("Badminton, Dinner, Trip…") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = amount, onValueChange = { amount = it }, singleLine = true,
                            label = { Text("Total amount ₹") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        if (groups.isNotEmpty()) {
                            Text("Your groups", fontSize = 13.sp, color = muted())
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                groups.forEach { g ->
                                    Pill("${g.name} · ${g.people.size}", { title = g.name; groupPeople = g.people },
                                        selected = groupPeople == g.people && title == g.name)
                                }
                            }
                        }
                    }
                    Button(
                        enabled = (amount.replace(",", "").toDoubleOrNull() ?: 0.0) > 0,
                        onClick = {
                            val v = amount.replace(",", "").toDouble()
                            scope.launch { saved = false; txn = Repo.addManual(ctx, title, v) }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Next — choose people") }
                }
            }
        }
    }
}

/** Step 3: one row per person with their share and a Send button. */
@Composable
private fun RequestStep(vm: MainVm, t: Txn, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val shares by produceState<List<Share>>(emptyList(), t.id) { value = vm.sharesFor(t.id) }
    val others = shares.filter { it.person != Repo.ME && it.amount > 0.004 }
    val asked = remember { mutableStateListOf<String>() }
    var sending by remember { mutableStateOf<Share?>(null) }
    val upi = Repo.getPref(ctx, "upi", "")
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Split saved ✓", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("${t.merchant} · ${Repo.money(t.amount)}", fontSize = 15.sp, color = muted())
                Spacer(Modifier.height(14.dp))
                if (others.isEmpty()) Text("Nobody else is in this split, so there's nothing to ask for.", color = muted())
                else Text("Ask everyone to pay. When their payment message arrives it's marked paid for you.", fontSize = 14.sp, color = muted())
                if (others.isNotEmpty() && upi.isBlank()) Text("Tip: add your UPI ID in Settings so the message has a pay link.", fontSize = 13.sp, color = amber(), modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { sending = s }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(s.person)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(s.person, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(Repo.money(s.amount), fontSize = 14.sp, color = muted())
                            }
                            if (s.person in asked) Text("Asked ✓", color = green(), fontSize = 14.sp)
                            else OutlinedButton(onClick = { sending = s }) { Text("Send") }
                        }
                    }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (others.isEmpty() || asked.isNotEmpty()) "Done" else "Skip for now") }
            }
        }
    }
    sending?.let { s ->
        ReminderDialog(s.person, { u -> Reminder.splitRequest(s.person, t.merchant, t.amount, s.amount, u) }) { asked.add(s.person); sending = null }
    }
}
