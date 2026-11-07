package com.sabharish.splittracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    @Test fun quickCommerceIsGroceryButSwiggyIsFood() {
        assertEquals("Grocery", Categories.guess("Swiggy Instamart"))
        assertEquals("Grocery", Categories.guess("RSP*INSTAMART"))
        assertEquals("Food", Categories.guess("SWIGGY"))
        assertEquals("Food", Categories.guess("Zomato Ltd"))
    }

    @Test fun commonMerchants() {
        assertEquals("Cab", Categories.guess("Uber India"))
        assertEquals("Grocery", Categories.guess("AVENUE SUPERMARTS LTD"))
        assertEquals("Travel", Categories.guess("IRCTC Web UPI"))
        assertEquals("Travel", Categories.guess("BMTC"))
        assertEquals("Dine Out", Categories.guess("FLAVOURS CAFE BENGALURU"))
        assertEquals("Shopping", Categories.guess("AMAZON"))
        assertEquals("Health", Categories.guess("Medplus Health"))
        assertEquals("Entertainment", Categories.guess("BIGTREE ENTERTA"))
        assertEquals("Fuel", Categories.guess("Indian Oil Petrol Pump"))
    }

    @Test fun unknownMerchantsStayUntagged() {
        assertEquals("", Categories.guess("Namma Irani"))
        assertEquals("", Categories.guess("Mungi Srikanth R"))
        assertEquals("", Categories.guess(""))
    }

    @Test fun credPaymentsAreBillsThroughTheAlias() {
        assertEquals("CRED", Categories.builtinAlias("RAZDREAMPLUGPAYTECHSOL"))
        assertEquals("Bills", Categories.guess("RAZDREAMPLUGPAYTECHSOL"))
        assertNull(Categories.builtinAlias("Swiggy"))
    }

    @Test fun onlyInstamartIsMineByDefault() {
        assertTrue(Categories.defaultMine("Swiggy Instamart"))
        assertTrue(Categories.defaultMine("RSP*INSTAMART"))
        assertFalse(Categories.defaultMine("Swiggy"))
        assertFalse(Categories.defaultMine("Zepto"))
    }

    @Test fun builtInListEndsWithOther() {
        assertEquals("Other", Categories.all.last())
        assertTrue(Categories.all.containsAll(listOf("Grocery", "Cab", "Dine Out", "Food")))
    }
}

class TagRenameAndDeleteKeyTest {
    @org.junit.Test fun foodOrderIsNowFood() {
        org.junit.Assert.assertFalse("Food Order" in Categories.all)
        org.junit.Assert.assertTrue("Food" in Categories.all)
        org.junit.Assert.assertEquals("Food", Categories.renamed["Food Order"])
    }
    @org.junit.Test fun oldCustomTagNameIsMappedAndNotDuplicated() {
        val tags = Logic.allTags(listOf("Food Order", "Pets"))
        org.junit.Assert.assertEquals(1, tags.count { it == "Food" })
        org.junit.Assert.assertTrue("Pets" in tags)
    }
    @org.junit.Test fun deletedKeyIsStableAndDistinguishes() {
        val a = Logic.deletedKey("SPEND", 250.0, 1000L)
        org.junit.Assert.assertEquals(a, Logic.deletedKey("SPEND", 250.004, 1000L))
        org.junit.Assert.assertNotEquals(a, Logic.deletedKey("SPEND", 250.0, 1001L))
        org.junit.Assert.assertNotEquals(a, Logic.deletedKey("UPI_OUT", 250.0, 1000L))
    }
}
