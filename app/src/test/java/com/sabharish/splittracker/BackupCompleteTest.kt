package com.sabharish.splittracker

import org.junit.Assert.*
import org.junit.Test

class BackupCompleteTest {
    private fun txn(id: Long) = Txn(id = id, bank = "HDFC", card = "1234", kind = "UPI_OUT", amount = 100.0 + id, merchant = "m$id",
        txnDate = id, smsTime = id, status = "TAGGED")

    @Test fun manySharesForOnePersonAllSurvive() {
        val txns = (1L..60L).map { txn(it) }
        val shares = (1L..60L).flatMap { listOf(Share(it * 2, it, "Ravi", 50.0, 0.0), Share(it * 2 + 1, it, "Me", 50.0, 50.0)) }
        val back = Backup.fromJson(Backup.toJson(BackupData(txns, shares, emptyList(), emptyList(), emptyMap()), 1L))!!
        assertEquals(60, back.txns.size)
        assertEquals(120, back.shares.size)
        assertEquals(60, back.shares.count { it.person == "Ravi" })
    }

    @Test fun settingsPhoneNumbersAndFlagsSurvive() {
        val prefs = mapOf("upi" to "me@bank", "tags" to "Gym,Pets", "phone|ravi" to "9876543210", "trackFrom" to "1700000000000", "groups" to "Badminton|Ravi,Anu")
        val back = Backup.fromJson(Backup.toJson(BackupData(emptyList(), emptyList(), emptyList(), emptyList(), prefs, flags = mapOf("imported2" to true)), 1L))!!
        assertEquals(prefs, back.prefs)
        assertEquals(mapOf("imported2" to true), back.flags)
    }

    @Test fun onlyPhoneSpecificSettingsAreLeftOut() {
        assertFalse(Backup.keep("lastBackup")); assertFalse(Backup.keep("unparsed"))
        listOf("upi", "tags", "hiddenTags", "groups", "selfnames", "theme", "streak", "trackFrom", "phone|ravi", "imported2").forEach { assertTrue(it, Backup.keep(it)) }
    }

    @Test fun describeTellsWhatIsInside() {
        val shares = listOf(Share(1, 1, "Ravi", 50.0, 0.0), Share(2, 1, "Anu", 50.0, 0.0), Share(3, 1, "Me", 50.0, 50.0))
        val text = Backup.toJson(BackupData(listOf(txn(1)), shares, emptyList(), listOf(Rule("m|x", "MINE", "Me")), mapOf("upi" to "a@b", "tags" to "Gym,Pets")), 1L)
        val d = Backup.describe(text)!!
        assertTrue(d, d.contains("1 transactions") && d.contains("2 people") && d.contains("2 own tags") && d.contains("UPI ID saved") && d.contains("1 remembered choices"))
        assertNull(Backup.describe("not json"))
    }
}
