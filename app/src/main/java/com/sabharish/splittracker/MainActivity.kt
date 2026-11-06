package com.sabharish.splittracker

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.loadContacts()
        catchUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(
            arrayOf(
                Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS
            )
        )
        ThemeState.mode = Repo.getPref(this, "theme", "system")
        setContent { MaterialTheme(colorScheme = if (isDarkNow()) DarkColors else LightColors) { App(vm) } }
    }

    override fun onStop() {
        super.onStop()
        Epoch.value++
    }

    override fun onResume() {
        super.onResume()
        vm.loadContacts()
        catchUp()
    }

    /** Picks up anything the live receiver missed. First run reads the last 90 days. */
    private fun catchUp() {
        if (!Repo.hasPermission(this, Manifest.permission.READ_SMS)) return
        val sp = getSharedPreferences("st", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // people who were already using the app keep tracking from their first import
        if (Repo.getPref(this, "trackFrom", "").isEmpty() && sp.getBoolean("imported2", false)) Repo.putPref(this, "trackFrom", (now - 90 * Bills.DAY).toString())
        val from = Repo.getPref(this, "trackFrom", "").toLongOrNull() ?: return   // new person: wait for the welcome choice
        vm.importHistory(maxOf(from, now - 7 * Bills.DAY), quiet = true)
        sp.edit().putBoolean("imported2", true).apply()
    }
}

@Composable
fun App(vm: MainVm) {
    var tab by remember { mutableIntStateOf(0) }
    var inboxSection by remember { mutableIntStateOf(0) }
    var searching by remember(tab) { mutableStateOf(false) }
    var query by remember(tab) { mutableStateOf("") }
    val spends by vm.inbox.collectAsState()
    val money by vm.moneyIn.collectAsState()
    val status by vm.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    val undoable: (Long, String) -> Unit = { id, msg ->
        scope.launch {
            val r = snackbar.showSnackbar(msg, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.untag(id)
        }
    }
    LaunchedEffect(status) {
        if (status.isNotBlank()) {
            val s = status
            vm.clearStatus()
            snackbar.showSnackbar(s)
        }
    }

    androidx.activity.compose.BackHandler(enabled = tab != 0) { tab = 0 }
    val titles = listOf("Today", "Inbox", "Owed to you", "Transactions", "Summary")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        androidx.compose.material3.BadgedBox(badge = { if (spends.size + money.size > 0) androidx.compose.material3.Badge { Text("${spends.size + money.size}") } }) {
                            Icon(Icons.Default.Email, null)
                        }
                    }, label = { Text("Inbox") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("Owed") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.List, null) }, label = { Text("All") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 },
                    icon = { Icon(Icons.Default.DateRange, null) }, label = { Text("Summary") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(titles[tab], fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tab in 1..3) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            }
            if (searching && tab in 1..3) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (tab == 2) "Search a person or bill" else "Name, amount, tag or message") }
                )
            }
            Spacer(Modifier.height(6.dp))
            when (tab) {
                0 -> HomeScreen(vm) { if (it == 11) { inboxSection = 3; tab = 1 } else { if (it == 1) inboxSection = 0; tab = it } }
                1 -> InboxScreen(vm, undoable, query, inboxSection) { inboxSection = it }
                2 -> PendingScreen(vm, query)
                3 -> AllScreen(vm, query)
                else -> SummaryScreen(vm)
            }
        }
    }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var welcome by remember { mutableStateOf(Repo.getPref(appCtx, "trackFrom", "").isEmpty() && !appCtx.getSharedPreferences("st", Context.MODE_PRIVATE).getBoolean("imported2", false)) }
    if (welcome) WelcomeDialog { from ->
        Repo.putPref(appCtx, "trackFrom", from.toString())
        vm.importHistory(from)
        welcome = false
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}
