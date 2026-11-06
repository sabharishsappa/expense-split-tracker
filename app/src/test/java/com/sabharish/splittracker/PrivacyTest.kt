package com.sabharish.splittracker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design rule: no data ever leaves the phone. The app must not be able to use the network at all. */
class PrivacyTest {
    private fun manifest() = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")

    @Test fun appHasNoInternetPermission() {
        val m = manifest(); assertTrue("manifest not found at ${m.absolutePath}", m.exists())
        val text = m.readText()
        assertFalse(text.contains("android.permission.INTERNET"))
        assertFalse(text.contains("ACCESS_NETWORK_STATE"))
    }
    @Test fun noCloudBackupOfAppData() {
        assertTrue(manifest().readText().contains("android:allowBackup=\"false\""))
    }
    @Test fun noNetworkLibrariesInSource() {
        val src = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
        val bad = src.walkTopDown().filter { it.extension == "kt" }.filter { f ->
            val t = f.readText(); listOf("java.net.URL", "HttpURLConnection", "okhttp3", "retrofit2", "firebase").any { t.contains(it) }
        }.map { it.name }.toList()
        assertTrue("network code found in $bad", bad.isEmpty())
    }
}
