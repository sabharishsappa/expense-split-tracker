package com.sabharish.splittracker

/**
 * Banks the app recognises from the sender name on a message ("VM-HDFCBK-S" -> HDFC) or from the bank's name in the text.
 * Short codes are matched on the sender only, so an ordinary word in a message never picks a bank.
 */
object Banks {
    private class B(val key: String, val senders: List<String>, val names: List<String>)

    private val all = listOf(
        B("SBI", listOf("SBIUPI", "SBIINB", "SBICRD", "SBIPSG", "SBIBNK", "ATMSBI", "SBMSMS", "SBI"), listOf("State Bank of India", "SBI Card", "SBI")),
        B("HDFC", listOf("HDFCBK", "HDFCBN", "HDFCCC", "HDFC"), listOf("HDFC Bank", "HDFC")),
        B("ICICI", listOf("ICICIB", "ICICIT", "ICICI"), listOf("ICICI Bank", "ICICI")),
        B("AXIS", listOf("AXISBK", "AXISBN", "AXISCC", "AXIS"), listOf("Axis Bank", "Axis")),
        B("KOTAK", listOf("KOTAKB", "KOTAK", "KMBL"), listOf("Kotak")),
        B("PNB", listOf("PNBSMS", "PUNBNB", "PNBBNK", "PNB"), listOf("Punjab National Bank", "PNB")),
        B("BOB", listOf("BOBTXN", "BOBSMS", "BOBCRD", "BARODA", "BOB"), listOf("Bank of Baroda", "BOB")),
        B("CANARA", listOf("CANBNK", "CANARA", "CNRB"), listOf("Canara Bank", "Canara")),
        B("UNION", listOf("UNIONB", "UBOI", "UNIONBK"), listOf("Union Bank of India", "Union Bank")),
        B("IDFC", listOf("IDFCFB", "IDFCBK", "IDFC"), listOf("IDFC FIRST", "IDFC")),
        B("INDUSIND", listOf("INDUSB", "INDUSI", "INDUSIND"), listOf("IndusInd")),
        B("YES", listOf("YESBNK", "YESBK", "YESBNK"), listOf("YES BANK")),
        B("FEDERAL", listOf("FEDBNK", "FEDBK", "FEDERA"), listOf("Federal Bank")),
        B("BOI", listOf("BOIIND", "BOIBNK", "BOI"), listOf("Bank of India")),
        B("IOB", listOf("IOBCHN", "IOBSMS", "IOB"), listOf("Indian Overseas Bank")),
        B("INDIAN", listOf("INDBNK", "INDIAN"), listOf("Indian Bank")),
        B("CENTRAL", listOf("CBSSBI", "CENTBK", "CBOI"), listOf("Central Bank of India")),
        B("UCO", listOf("UCOBNK", "UCOBK", "UCO"), listOf("UCO Bank")),
        B("AU", listOf("AUBANK", "AUSFBL", "AUSFB"), listOf("AU Small Finance", "AU Bank")),
        B("RBL", listOf("RBLBNK", "RBLCRD", "RBL"), listOf("RBL Bank")),
        B("BANDHAN", listOf("BANDHN", "BANDHAN"), listOf("Bandhan Bank")),
        B("PAYTM", listOf("PAYTMB", "PYTM", "PAYTM"), listOf("Paytm Payments Bank", "Paytm")),
        B("AIRTEL", listOf("AIRBNK", "AIRTLB"), listOf("Airtel Payments Bank")),
        B("IPPB", listOf("IPBMSG", "IPPBMS", "IPPB"), listOf("India Post Payments Bank")),
        B("SIB", listOf("SIBSMS", "SOUTHI"), listOf("South Indian Bank")),
        B("TMB", listOf("TMBANK", "TMBSMS"), listOf("Tamilnad Mercantile")),
        B("KVB", listOf("KVBANK", "KARURV"), listOf("Karur Vysya")),
        B("DCB", listOf("DCBBNK", "DCBANK"), listOf("DCB Bank")),
        B("DBS", listOf("DBSBNK", "DBSBANK", "DBS"), listOf("DBS Bank", "DBS")),
        B("CITI", listOf("CITIBK", "CITIBN", "CITI"), listOf("Citibank")),
        B("HSBC", listOf("HSBCIN", "HSBC"), listOf("HSBC")),
        B("SCB", listOf("SCBANK", "STDCHR"), listOf("Standard Chartered")),
        B("AMEX", listOf("AMEXIN", "AMEXCA", "AMEX"), listOf("American Express")),
        B("JKB", listOf("JKBANK", "JKBNK"), listOf("J&K Bank", "Jammu & Kashmir Bank"))
    )

    /** "VM-HDFCBK-S" -> "HDFCBK": the middle part of an Indian sender id, or the whole thing if it has no dashes. */
    fun senderCode(sender: String?): String {
        val parts = (sender ?: "").uppercase().trim().split('-').filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 -> parts[1]
            parts.size == 2 -> if (parts[0].length <= 2) parts[1] else parts[0]
            parts.size == 1 -> parts[0]
            else -> ""
        }
    }

    fun fromSender(sender: String?): String? {
        val code = senderCode(sender)
        if (code.isEmpty()) return null
        return all.firstOrNull { b -> b.senders.any { code == it || (it.length >= 4 && code.startsWith(it)) } }?.key
    }

    fun fromText(text: String): String? {
        val up = text.uppercase()
        // longest names first, so "HDFC Bank" is found before a lone "HDFC"
        return all.flatMap { b -> b.names.map { b.key to it } }.sortedByDescending { it.second.length }
            .firstOrNull { (_, n) -> Regex("""\b${Regex.escape(n.uppercase())}\b""").containsMatchIn(up) }?.first
    }

    fun detect(text: String, sender: String?): String? = fromSender(sender) ?: fromText(text)
}
