package com.sabharish.splittracker

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

object AppLock {
    const val KEY = "lock"
    fun isOn(ctx: Context) = Repo.getPref(ctx, KEY, "") == "1"
    fun canUse(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    @Suppress("DEPRECATION")
    fun intent(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)
        ?.createConfirmDeviceCredentialIntent("Paisa Log", "Unlock to see your money")
}

/** Covers the app with the phone's own screen lock (fingerprint, face, PIN) when the lock is on. Nothing is stored. */
@Composable
fun LockGate() {
    val ctx = LocalContext.current
    var locked by remember { mutableStateOf(AppLock.isOn(ctx) && AppLock.canUse(ctx)) }
    var stoppedAt by remember { mutableLongStateOf(0L) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) stoppedAt = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START && LockRule.shouldLock(AppLock.isOn(ctx) && AppLock.canUse(ctx), stoppedAt, System.currentTimeMillis())) locked = true
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) locked = false }
    fun ask() { val i = AppLock.intent(ctx); if (i == null) locked = false else launcher.launch(i) }
    LaunchedEffect(locked) { if (locked) ask() }
    if (locked) FullScreenDialog({}) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔒", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("Paisa Log is locked", fontSize = 20.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { ask() }) { Text("Unlock") }
            }
        }
    }
}
