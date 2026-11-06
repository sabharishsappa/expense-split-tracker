package com.sabharish.splittracker

import java.util.Locale

object Categories {
    val all = listOf(
        "Grocery", "Food Order", "Dine Out", "Cab", "Travel", "Shopping",
        "Bills", "Fuel", "Entertainment", "Health", "Other"
    )

    private val rules: List<Pair<String, List<String>>> = listOf(
        // Quick-commerce first, so "Swiggy Instamart" is Grocery and plain "Swiggy" is Food Order
        "Grocery" to listOf("instamart", "blinkit", "zepto", "bigbasket", "big basket", "jiomart", "dmart", "avenue super",
            "ratnadeep", "supermarket", "super market", "supermart", "more retail", "nature's basket", "provision",
            "kirana", "vegetable", "fresh", "reliance smart", "spencer"),
        "Food Order" to listOf("swiggy", "zomato", "eatsure", "faasos", "box8", "domino", "pizza hut", "kfc", "mcdonald"),
        "Cab" to listOf("uber", "ola ", "olacabs", "ola money", "rapido", "namma yatri", "blusmart", "meru", "cab"),
        "Travel" to listOf("irctc", "railway", "makemytrip", "goibibo", "agoda", "redbus", "yatra", "indigo", "air india",
            "airline", "bmtc", "metro", "ksrtc", "quest trav", "cleartrip", "ixigo", "abhibus", "hotel"),
        "Dine Out" to listOf("restaurant", "cafe", "bakery", "biryani", "kitchen", "dhaba", "foods", "eazydiner", "dineout",
            "starbucks", "barbeque", "bbq", "tiffin", "udupi", "catering", "bar ", "pub ", "brew", "juice", "chai",
            "sweets", "meghana", "flavours", "tooth corne"),
        "Shopping" to listOf("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "decathlon", "lifestyle",
            "shoppers stop", "westside", "reliance retail", "croma", "tata cliq", "snapdeal", "pantaloons", "max fashion"),
        "Bills" to listOf("airtel", "jio", "vodafone", "bescom", "electricity", "broadband", "fibernet", "policybazaar",
            "insurance", "lic ", "recharge", "gas", "water board", "bharat bill", "cred", "bbps"),
        "Fuel" to listOf("petrol", "bpcl", "hpcl", "iocl", "indian oil", "shell", "fuel", "nayara"),
        "Entertainment" to listOf("bigtree", "bookmyshow", "netflix", "spotify", "hotstar", "prime video", "pvr", "inox",
            "youtube", "google play", "apple", "sony liv", "zee5", "gaming"),
        "Health" to listOf("medplus", "apollo", "pharmacy", "pharma", "1mg", "pharmeasy", "hospital", "clinic",
            "diagnostic", "dental", "lab", "medical")
    )

    fun guess(merchant: String): String {
        val m = " " + merchant.lowercase(Locale.ROOT) + " "
        for ((cat, words) in rules) {
            if (words.any { m.contains(it) }) return cat
        }
        return ""
    }
}
