package com.chipherlock.app

// ════════════════════════════════════════════════════════════════════════
// VerifiedSenders.kt
//
// Curated list of legitimate SMS sender IDs commonly used in the Philippines.
// These are alphanumeric short codes that real banks, telcos, e-wallets, and
// services use for one-way notifications. A scammer cannot easily spoof these
// because Philippine telcos route them through verified channels.
//
// IMPORTANT: this is NOT a whitelist that bypasses analysis. It only tags
// the sender with a "VERIFIED" trust tier — AI still scrutinizes the body,
// because a hijacked or impersonated brand name should still be caught.
// ════════════════════════════════════════════════════════════════════════

object VerifiedSenders {

    // Match is case-insensitive and uses exact equality against the trimmed sender.
    // Add new entries here as you encounter them in real-world testing.
    private val verified: Set<String> = setOf(
        // Banks
        "BDO", "BPI", "METROBANK", "UNIONBANK", "PNB", "RCBC",
        "SECURITY", "SECURITYBANK", "CHINABANK", "LANDBANK", "DBP",
        "EASTWEST", "MAYBANK", "AUB", "ROBINSONS",

        // E-wallets & payments
        "GCASH", "MAYA", "PAYMAYA", "GRABPAY", "COINSPH", "PAYPAL",

        // Telcos
        "GLOBE", "SMART", "TM", "TNT", "DITO", "PLDT", "SUN",

        // Ride / delivery
        "GRAB", "ANGKAS", "JOYRIDE", "FOODPANDA", "LALAMOVE", "MOVEIT",

        // E-commerce
        "LAZADA", "SHOPEE", "ZALORA", "CARMUDI",

        // Couriers
        "LBC", "JNT", "JRS", "2GO", "NINJAVAN", "FLASHEXPRESS",

        // Government / utility (rarely SMS but possible)
        "PHILHEALTH", "SSS", "GSIS", "MERALCO", "MANILAWATER", "MAYNILAD",

        // Misc
        "VIBER", "GOOGLE", "FB", "FACEBOOK", "INSTAGRAM", "WHATSAPP"
    )

    fun isVerified(sender: String): Boolean {
        val trimmed = sender.trim().uppercase()
        // Verified senders are always alphabetic short codes, never numeric.
        if (trimmed.isEmpty() || trimmed.all { it.isDigit() || it == '+' }) return false
        return trimmed in verified
    }
}