package com.chipherlock.app

// ---------------------------------------------------------------------------
// UssdParser.kt  (Phase 4.1 — Security Layer)
//
// Parses raw USSD responses from Globe/TM, Smart/TNT/Sun, and DITO
// into structured data. Falls back to raw text display if parsing fails.
//
// USSD responses are NEVER stored — this parser is used only for display.
// ---------------------------------------------------------------------------

object UssdParser {

    data class UssdResult(
        val balance:     String?,   // e.g. "₱45.50"
        val activePromo: String?,   // e.g. "Go+99"
        val expiry:      String?,   // e.g. "June 25, 2026"
        val dataBalance: String?,   // e.g. "3.2GB"
        val rawText:     String     // always kept — shown if parsing fails
    )

    fun parse(carrier: String, raw: String): UssdResult {
        val c = carrier.lowercase()
        return when {
            c.contains("globe") || c.contains("tm") -> parseGlobe(raw)
            c.contains("smart") || c.contains("tnt") || c.contains("sun") -> parseSmart(raw)
            c.contains("dito") -> parseDito(raw)
            else -> UssdResult(null, null, null, null, raw)
        }
    }

    // ── Globe / TM ───────────────────────────────────────────────────────────
    // Sample: "Balance: Php 45.50\nActive Promo: Go+99\nValid Until: 06/25/2026\nData: 3.2GB"

    private fun parseGlobe(raw: String): UssdResult {
        val balance     = extractPattern(raw, listOf(
            Regex("""[Bb]alance[:\s]+Php?\s*([\d.]+)"""),
            Regex("""Php?\s*([\d.]+)\s*remaining"""),
            Regex("""load[:\s]+Php?\s*([\d.]+)""")
        ))?.let { "₱$it" }

        val promo = extractPattern(raw, listOf(
            Regex("""[Aa]ctive [Pp]romo[:\s]+([A-Za-z0-9+\s]+)"""),
            Regex("""[Pp]romo[:\s]+([A-Za-z0-9+\s]+)"""),
            Regex("""(Go\+?\d+|GoUNLI\w+|DUO\w*)""")
        ))?.trim()

        val expiry = extractPattern(raw, listOf(
            Regex("""[Vv]alid [Uu]ntil[:\s]+(\d{2}/\d{2}/\d{4})"""),
            Regex("""[Ee]xpir[esy]+[:\s]+(\d{2}/\d{2}/\d{4})"""),
            Regex("""(\d{2}/\d{2}/\d{4})""")
        ))?.let { formatDate(it) }

        val data = extractPattern(raw, listOf(
            Regex("""[Dd]ata[:\s]+([\d.]+\s*[GgMm][Bb])"""),
            Regex("""([\d.]+\s*GB)\s*remaining""")
        ))

        return UssdResult(balance, promo, expiry, data, raw)
    }

    // ── Smart / TNT / Sun ────────────────────────────────────────────────────
    // Sample: "Your balance is Php45.50. Active promo: ALLIN99 valid until Jun 25."

    private fun parseSmart(raw: String): UssdResult {
        val balance = extractPattern(raw, listOf(
            Regex("""balance is Php?\s*([\d.]+)"""),
            Regex("""Php?\s*([\d.]+)"""),
            Regex("""load[:\s]+Php?\s*([\d.]+)""")
        ))?.let { "₱$it" }

        val promo = extractPattern(raw, listOf(
            Regex("""[Aa]ctive promo[:\s]+([A-Z0-9]+)"""),
            Regex("""(ALLIN\d+|UNLI\d+|UCT\d+|POWERALL\d+|UTP\d+)""")
        ))?.trim()

        val expiry = extractPattern(raw, listOf(
            Regex("""valid until ([A-Za-z]+\.?\s+\d{1,2},?\s*\d{0,4})"""),
            Regex("""expir[esy]+\s+([A-Za-z]+\.?\s+\d{1,2},?\s*\d{0,4})"""),
            Regex("""(\d{2}/\d{2}/\d{4})""")
        ))?.trim()

        val data = extractPattern(raw, listOf(
            Regex("""([\d.]+\s*GB)"""),
            Regex("""data[:\s]+([\d.]+\s*[GgMm][Bb])""")
        ))

        return UssdResult(balance, promo, expiry, data, raw)
    }

    // ── DITO ─────────────────────────────────────────────────────────────────
    // Sample: "Account Balance: Php 120.00\nLevel-Up 99 active\nExpiry: June 25, 2026"

    private fun parseDito(raw: String): UssdResult {
        val balance = extractPattern(raw, listOf(
            Regex("""[Aa]ccount [Bb]alance[:\s]+Php?\s*([\d.]+)"""),
            Regex("""Php?\s*([\d.]+)""")
        ))?.let { "₱$it" }

        val promo = extractPattern(raw, listOf(
            Regex("""(Level-Up \d+)"""),
            Regex("""[Aa]ctive[:\s]+([A-Za-z0-9\s-]+)""")
        ))?.trim()

        val expiry = extractPattern(raw, listOf(
            Regex("""[Ee]xpiry[:\s]+([A-Za-z]+ \d{1,2},? \d{4})"""),
            Regex("""(\d{2}/\d{2}/\d{4})""")
        ))

        val data = extractPattern(raw, listOf(
            Regex("""([\d.]+\s*GB)"""),
            Regex("""data[:\s]+([\d.]+\s*[GgMm][Bb])""")
        ))

        return UssdResult(balance, promo, expiry, data, raw)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractPattern(text: String, patterns: List<Regex>): String? {
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun formatDate(mmddyyyy: String): String {
        return try {
            val sdf = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US)
            val out = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US)
            out.format(sdf.parse(mmddyyyy) ?: return mmddyyyy)
        } catch (_: Exception) { mmddyyyy }
    }
}