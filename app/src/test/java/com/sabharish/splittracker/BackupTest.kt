package com.sabharish.splittracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val data = BackupData(
        txns = listOf(
            Txn(id = 7, bank = "SBI", card = "6674", kind = "SPEND", amount = 187.5, merchant = "Namma Irani", txnDate = 1000, smsTime = 2000,
                status = "TAGGED", splitAdded = true, category = "Dine Out", refundOf = 0, note = "lunch", auto = false),
            Txn(id = 9, bank = "ICICI", card = "395", kind = "REFUND", amount = 50.0, merchant = "Amazon, \"India\"", txnDate = 3000, smsTime = 4000,
                status = "REFUND", refundOf = 7, auto = true)
        ),
        shares = listOf(Share(1, 7, "Me", 37.5, 37.5), Share(2, 7, "Rajesh Kumar", 150.0, 40.0)),
        settlements = listOf(Settlement(1, "Rajesh Kumar", 40.0, 5000, "UPI")),
        rules = listOf(Rule("m|namma", "PEOPLE", "Me,Rajesh Kumar"), Rule("alias|raz", "ALIAS", "CRED")),
        prefs = mapOf("upi" to "me@okaxis", "tags" to "Gym,Pets", "theme" to "dark")
    )

    @Test fun roundTripKeepsEverything() {
        val back = Backup.fromJson(Backup.toJson(data, 123L))
        assertNotNull(back)
        assertEquals(data, back)
    }

    @Test fun customTagsAndUpiSurvive() {
        val back = Backup.fromJson(Backup.toJson(data, 1L))!!
        assertEquals(listOf("Gym", "Pets"), Logic.parseTags(back.prefs["tags"]!!))
        assertEquals("me@okaxis", back.prefs["upi"])
    }

    @Test fun emptyBackupRoundTrips() {
        val empty = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
        assertEquals(empty, Backup.fromJson(Backup.toJson(empty, 1L)))
    }

    @Test fun savedAtIsReadable() {
        assertEquals(987L, Backup.savedAt(Backup.toJson(data, 987L)))
    }

    @Test fun otherFilesAreRejected() {
        assertNull(Backup.fromJson("not json"))
        assertNull(Backup.fromJson("{\"hello\":1}"))
        assertNull(Backup.fromJson(""))
    }

    @Test fun backedUpPrefKeysIncludeTheImportantOnes() {
        assertTrue(Backup.PREF_KEYS.containsAll(listOf("upi", "selfnames", "tags", "theme")))
    }
}

class BackupBillsTest {
    @Test fun billsSurviveBackup() {
        val b = Bill(5, "ICICI", "6007", 1234.5, 100.0, 1000L, 900L, true)
        val back = Backup.fromJson(Backup.toJson(BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), listOf(b)), 1L))!!
        org.junit.Assert.assertEquals(listOf(b), back.bills)
    }
    @Test fun oldBackupWithoutBillsStillLoads() {
        val back = Backup.fromJson("""{"app":"SplitTracker","version":1,"txns":[],"shares":[],"settlements":[],"rules":[],"prefs":{}}""")!!
        org.junit.Assert.assertTrue(back.bills.isEmpty())
    }
}
