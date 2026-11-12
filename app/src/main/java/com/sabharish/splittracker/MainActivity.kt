package com.sabharish.splittracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
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
    companion object { const val EXTRA_TXN = "txnId" }

    private fun readIntent(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_TXN, 0L) ?: 0L
        if (id > 0) { vm.openId.value = id; i?.removeExtra(EXTRA_TXN) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private val vm: MainVm by viewModels()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Perm.tick++
        vm.loadContacts()
        catchUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a brand-new person sees the welcome choice first; permissions are asked right after it
        if (Repo.getPref(this, "trackFrom", "").isNotEmpty() || getSharedPreferences("st", Context.MODE_PRIVATE).getBoolean("imported2", false)) askPermissions()
        readIntent(intent)
        ThemeState.mode = Repo.getPref(this, "theme", "system")
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = if (isDarkNow()) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes) { App(vm) { askPermissions() } } }
    }

    private fun askPermissions() = perms.launch(
        arrayOf(
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS
        )
    )

    override fun onStop() {
        super.onStop()
        Epoch.value++
    }

    override fun onResume() {
        super.onResume()
        Perm.tick++
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
        val first = !sp.getBoolean("imported2", false)
        vm.importHistory(if (first) from else maxOf(from, now - 7 * Bills.DAY), quiet = !first)
        sp.edit().putBoolean("imported2", true).apply()
    }
}

@Composable
fun App(vm: MainVm, askPermissions: () -> Unit = {}) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalScreenInsets provides androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues()
    ) { AppBody(vm, askPermissions) }
}

@Composable
private fun AppBody(vm: MainVm, askPermissions: () -> Unit) {
    // bottom bar like Money Manager: Trans. / Stats / Accounts / More
    var tab by remember { mutableIntStateOf(0) }
    // inside Trans.: 0 Inbox, 1 Daily, 2 Calendar, 3 Monthly, 4 Total, 5 Note
    var sub by remember { mutableIntStateOf(1) }
    var accSection by remember { mutableIntStateOf(0) }
    var monthOffset by remember { mutableIntStateOf(0) }
    var flt by remember { mutableStateOf(TxnFilter()) }
    var showFilters by remember { mutableStateOf(false) }
    var inboxSection by remember { mutableIntStateOf(0) }
    var showNewSplit by remember { mutableStateOf(false) }
    var showEntry by remember { mutableStateOf(false) }
    var showStatement by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Txn?>(null) }
    val openId by vm.openId.collectAsState()
    var searching by remember(tab, sub, accSection) { mutableStateOf(false) }
    var query by remember(tab, sub, accSection) { mutableStateOf("") }
    val spends by vm.inbox.collectAsState()
    val money by vm.moneyIn.collectAsState()
    val status by vm.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val tags by vm.tags.collectAsState()
    val today = remember { java.util.Calendar.getInstance() }
    val (y, m) = Stats.shift(today.get(java.util.Calendar.YEAR), today.get(java.util.Calendar.MONTH), monthOffset)
    val inboxCount = spends.size + money.size

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
    LaunchedEffect(openId) {
        if (openId > 0) {
            val t = vm.txnById(openId); vm.openId.value = 0
            tab = 0; sub = 0
            if (t != null) when {
                t.kind == "UPI_IN" || t.kind == "REFUND" || t.status == "REPAID" -> inboxSection = 1
                t.status == "INBOX" || t.status == "TAGGED" -> { inboxSection = 0; opened = t }
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = tab != 0) { tab = 0 }
    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = coral(), selectedTextColor = coral(),
        unselectedIconColor = muted(), unselectedTextColor = muted(), indicatorColor = Color.Transparent
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, colors = navColors,
                    icon = {
                        androidx.compose.material3.BadgedBox(badge = { if (inboxCount > 0) androidx.compose.material3.Badge { Text("$inboxCount") } }) { Icon(Icons.AutoMirrored.Filled.List, null) }
                    }, label = { Text("Trans.", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, colors = navColors, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Owed", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, colors = navColors, icon = { Icon(IconWallet, null) }, label = { Text("Accounts", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, colors = navColors, icon = { Icon(IconBars, null) }, label = { Text("Stats", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, colors = navColors, icon = { Icon(IconMore, null) }, label = { Text("More", fontSize = 12.sp) })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val loaded by vm.loaded.collectAsState()
            if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } else
            Column(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> {
                        val actions: @Composable () -> Unit = {
                            IconButton(onClick = { showNewSplit = true }) { Icon(SplitIcon, contentDescription = "Split a bill") }
                            SearchIconButton(searching) { searching = !searching; if (!searching) query = "" }
                            if (sub in 1..5) IconButton(onClick = { showFilters = !showFilters }) { Icon(IconTune, contentDescription = "Filter", tint = if (flt.active) coral() else MaterialTheme.colorScheme.onSurface) }
                        }
                        MonthBar(if (sub == 0) "Drafts" else "${Stats.monthLabel(m)} $y",
                            { monthOffset -= 1 }, { if (monthOffset < 0) monthOffset += 1 }, monthOffset < 0, arrows = sub != 0, actions = actions)
                        val subs = listOf(0, 1, 4, 5)
                        MmTabs(listOf(if (inboxCount > 0) "Drafts $inboxCount" else "Drafts", "Activity", "Total", "Note"), subs.indexOf(sub).coerceAtLeast(0)) { sub = subs[it] }
                        if (searching) Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { SearchBox(query, { query = it }, "Name, amount, tag or message") }
                        if (showFilters && sub in 1..5) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Type", Filters.types, flt.type) { flt = flt.copy(type = it) }
                            FilterPill("Tag", listOf("Any", "Untagged") + tags, if (flt.tagState == "Untagged") "Untagged" else flt.tag.ifBlank { "Any" }) {
                                flt = when (it) { "Any" -> flt.copy(tagState = "Any", tag = ""); "Untagged" -> flt.copy(tagState = "Untagged", tag = ""); else -> flt.copy(tagState = "Any", tag = it) }
                            }
                            FilterPill("Status", Filters.statuses, flt.status) { flt = flt.copy(status = it) }
                            if (flt.active) Pill("Clear ✕", { flt = TxnFilter() }, compact = true)
                        }
                        val txns by vm.all.collectAsState()
                        val shareList by vm.shares.collectAsState()
                        if (sub == 1 || sub == 5) {
                            val byTxn = remember(shareList) { shareList.groupBy { it.txnId } }
                            SummaryStrip(Stats.incomeInMonth(txns, y, m), Stats.spentInMonth(txns, byTxn, y, m))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (sub == 0 || sub == 4) 14.dp else 0.dp)) {
                            when (sub) {
                                0 -> Column(Modifier.fillMaxSize()) { InboxScreen(vm, undoable, query, inboxSection) { inboxSection = it } }
                                1 -> DailyPane(vm, y, m, query, flt, onOpenDrafts = { sub = 0 })
                                2 -> CalendarMm(vm, y, m, today)
                                3 -> MonthlyPane(vm, y) { mo -> monthOffset = (mo - today.get(java.util.Calendar.MONTH)) + 12 * (y - today.get(java.util.Calendar.YEAR)); sub = 1 }
                                4 -> HomeScreen(vm) { code ->
                                    when (code) {
                                        1 -> { inboxSection = 0; sub = 0 }
                                        2 -> tab = 3
                                        4 -> tab = 1
                                        11 -> { inboxSection = 3; sub = 0 }
                                    }
                                }
                                else -> NotePane(vm, y, m)
                            }
                        }
                    }
                    1 -> Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                        Text("Stats", fontSize = 20.sp, modifier = Modifier.padding(vertical = 16.dp))
                        SummaryScreen(vm)
                    }
                    2 -> {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Accounts", fontSize = 20.sp, modifier = Modifier.weight(1f))
                            SearchIconButton(searching) { searching = !searching; if (!searching) query = "" }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { AccountsPage(vm, query, 0) { } }
                    }
                    3 -> {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Owed", fontSize = 20.sp, modifier = Modifier.weight(1f))
                            SearchIconButton(searching) { searching = !searching; if (!searching) query = "" }
                        }
                        if (searching) Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { SearchBox(query, { query = it }, "Search a person or bill") }
                        Box(Modifier.weight(1f).fillMaxWidth()) { PendingScreen(vm, query) }
                    }
                    else -> MorePage(vm, openSettings = { showSettings = true }, openStatement = { showStatement = true })
                }
            }
            if (loaded && ((tab == 0 && sub != 0) || (tab == 2) || tab == 1)) {
                RoundAddButton({ showEntry = true }, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp))
            }
        }
    }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var welcome by remember { mutableStateOf(Repo.getPref(appCtx, "trackFrom", "").isEmpty() && !appCtx.getSharedPreferences("st", Context.MODE_PRIVATE).getBoolean("imported2", false)) }
    var askName by remember { mutableStateOf(Repo.getPref(appCtx, "selfAsked", "").isEmpty()) }
    if (welcome) WelcomeDialog(
        onRestore = { text, done ->
            // a restored phone already has its name and settings, so skip the name question too
            vm.restore(text) { ok ->
                if (ok) { Repo.putPref(appCtx, "selfAsked", "1"); askName = false; welcome = false; askPermissions() }
                done(ok)
            }
        },
        onPick = { from ->
            Repo.putPref(appCtx, "trackFrom", from.toString())
            welcome = false
            askPermissions()
        }
    )
    if (!welcome && askName) NameDialog { n ->
        if (n.isNotEmpty()) Repo.putPref(appCtx, "selfnames", n)
        Repo.putPref(appCtx, "selfAsked", "1"); askName = false
    }
    if (showNewSplit) NewSplitFlow(vm) { showNewSplit = false }
    if (showEntry) AddEntryScreen(vm) { showEntry = false }
    if (showStatement) StatementFlow(vm) { showStatement = false }
    opened?.let { t -> TagSheet(vm, t, onDismiss = { opened = null }, onSaved = { undoable(t.id, it) }) }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

