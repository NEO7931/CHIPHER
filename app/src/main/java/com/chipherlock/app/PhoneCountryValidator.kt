package com.chipherlock.app

// ═══════════════════════════════════════════════════════════════════════════
// PhoneCountryValidator.kt — Phase 3.3
//
// Validates the country code of an SMS sender number and applies a trust
// penalty if the message appears to come from an unexpected country.
//
// DESIGN DECISIONS:
//
//   WHY NOT libphonenumber?
//   libphonenumber (google/libphonenumber) adds ~2MB to the APK and requires
//   a dependency. For CHIPHERLOCK's use case — detecting foreign country code
//   prefixes on E.164 numbers — a lightweight prefix table is sufficient and
//   has zero external dependencies. libphonenumber is overkill here.
//   This is documented as a known trade-off; if deeper number validation is
//   needed in Phase 4+, libphonenumber can be added then.
//
//   PENALTY DESIGN:
//   +15 points — not an auto-flag. A foreign number on an otherwise clean
//   message stays LOW. A foreign number on a message that already has keyword
//   hits may push it from MEDIUM to HIGH. This matches the roadmap spec.
//
//   SCOPE:
//   Only applies to numeric senders that start with + (E.164 format).
//   Alphanumeric senders (BPI, GCASH, GLOBE) are ALWAYS skipped — these are
//   short codes registered in the Philippines and cannot be spoofed by country.
//   Philippine numbers (+63) are always trusted — zero penalty.
//   Unknown/unrecognised country codes get the full penalty.
//
//   TRAVELLING MODE:
//   Stored in SharedPreferences ("roaming_prefs" / "travelling_mode").
//   When ON, foreign number penalty is suppressed entirely.
//   The user can toggle this in Settings (Phase 4 UI — for now it defaults OFF).
// ═══════════════════════════════════════════════════════════════════════════

import android.content.Context

object PhoneCountryValidator {

    // ── Known country code prefixes — ordered longest-first for greedy match ──
    // Source: ITU-T E.164 country code assignments (public domain)
    // https://www.itu.int/pub/T-SP-E.164D
    //
    // Only includes codes most commonly used in scams targeting the Philippines.
    // The +63 Philippine prefix is handled separately (trusted — zero penalty).

    data class CountryEntry(
        val prefix:      String,
        val countryName: String,
        val riskNote:    String   // shown in the score factor label
    )

    private val countryTable: List<CountryEntry> = listOf(
        // ── Philippines (trusted) ─────────────────────────────────────────
        CountryEntry("+63",  "Philippines",  "trusted"),

        // ── Common scam source countries (high-risk contexts) ─────────────
        CountryEntry("+86",  "China",        "foreign number (+86 China)"),
        CountryEntry("+855", "Cambodia",     "foreign number (+855 Cambodia)"),
        CountryEntry("+95",  "Myanmar",      "foreign number (+95 Myanmar)"),
        CountryEntry("+62",  "Indonesia",    "foreign number (+62 Indonesia)"),
        CountryEntry("+60",  "Malaysia",     "foreign number (+60 Malaysia)"),
        CountryEntry("+66",  "Thailand",     "foreign number (+66 Thailand)"),
        CountryEntry("+84",  "Vietnam",      "foreign number (+84 Vietnam)"),
        CountryEntry("+91",  "India",        "foreign number (+91 India)"),
        CountryEntry("+92",  "Pakistan",     "foreign number (+92 Pakistan)"),
        CountryEntry("+234", "Nigeria",      "foreign number (+234 Nigeria)"),
        CountryEntry("+233", "Ghana",        "foreign number (+233 Ghana)"),
        CountryEntry("+27",  "South Africa", "foreign number (+27 South Africa)"),
        CountryEntry("+44",  "UK",           "foreign number (+44 UK)"),
        CountryEntry("+1",   "US/Canada",    "foreign number (+1 US/Canada)"),
        CountryEntry("+7",   "Russia",       "foreign number (+7 Russia)"),
        CountryEntry("+380", "Ukraine",      "foreign number (+380 Ukraine)"),
        CountryEntry("+90",  "Turkey",       "foreign number (+90 Turkey)"),
        CountryEntry("+212", "Morocco",      "foreign number (+212 Morocco)"),
        CountryEntry("+20",  "Egypt",        "foreign number (+20 Egypt)"),
        CountryEntry("+55",  "Brazil",       "foreign number (+55 Brazil)"),
        CountryEntry("+52",  "Mexico",       "foreign number (+52 Mexico)"),
        CountryEntry("+65",  "Singapore",    "foreign number (+65 Singapore)"),
        CountryEntry("+82",  "South Korea",  "foreign number (+82 South Korea)"),
        CountryEntry("+81",  "Japan",        "foreign number (+81 Japan)"),
        CountryEntry("+886", "Taiwan",       "foreign number (+886 Taiwan)"),
        CountryEntry("+852", "Hong Kong",    "foreign number (+852 Hong Kong)"),
        CountryEntry("+853", "Macau",        "foreign number (+853 Macau)"),
        CountryEntry("+61",  "Australia",    "foreign number (+61 Australia)"),
        CountryEntry("+64",  "New Zealand",  "foreign number (+64 New Zealand)")
    )

    // Sorted longest-prefix-first for greedy matching
    private val sortedTable = countryTable.sortedByDescending { it.prefix.length }

    // ── Penalty configuration ─────────────────────────────────────────────────
    const val FOREIGN_NUMBER_PENALTY = 15
    const val UNKNOWN_COUNTRY_PENALTY = 15

    // ── Public API ────────────────────────────────────────────────────────────

    data class ValidationResult(
        val shouldPenalise: Boolean,
        val penalty:        Int,
        val factorLabel:    String,   // shown in ScoreBreakdownUI evidence list
        val countryName:    String    // "Philippines", "China", "Unknown", etc.
    )

    /**
     * Validates the sender number and returns a ValidationResult.
     *
     * Returns shouldPenalise = false for:
     *   - Alphanumeric senders (BPI, GCASH, etc.)
     *   - Philippine numbers (+63...)
     *   - Numbers that don't start with + (local format, short codes)
     *   - Travelling mode ON
     *
     * Returns shouldPenalise = true for:
     *   - Any + number not starting with +63
     *
     * @param sender       The SMS originating address
     * @param ctx          Application context (for SharedPreferences)
     */
    fun validate(sender: String, ctx: Context): ValidationResult {
        val trimmed = sender.trim()

        // Skip alphanumeric senders — these are registered short codes
        if (!trimmed.startsWith("+")) {
            return ValidationResult(false, 0, "", "Alphanumeric/local")
        }

        // Check travelling mode — suppress penalty if user is abroad
        val isTravelling = ctx.getSharedPreferences("roaming_prefs", Context.MODE_PRIVATE)
            .getBoolean("travelling_mode", false)
        if (isTravelling) {
            return ValidationResult(false, 0, "", "Travelling mode")
        }

        // Greedy prefix match
        val entry = sortedTable.firstOrNull { trimmed.startsWith(it.prefix) }

        return when {
            entry == null -> {
                // Unrecognised country code
                ValidationResult(
                    shouldPenalise = true,
                    penalty        = UNKNOWN_COUNTRY_PENALTY,
                    factorLabel    = "Foreign number (unrecognised country code)",
                    countryName    = "Unknown"
                )
            }
            entry.prefix == "+63" -> {
                // Philippine number — trusted
                ValidationResult(false, 0, "", "Philippines")
            }
            else -> {
                // Known foreign country
                ValidationResult(
                    shouldPenalise = true,
                    penalty        = FOREIGN_NUMBER_PENALTY,
                    factorLabel    = "Sender from ${entry.riskNote}",
                    countryName    = entry.countryName
                )
            }
        }
    }

    /**
     * Convenience: returns true if the sender is a Philippine number.
     * Used by SmsReceiver to skip penalty for trusted PH numbers quickly.
     */
    fun isPhilippineNumber(sender: String): Boolean {
        val trimmed = sender.trim()
        return trimmed.startsWith("+63") || !trimmed.startsWith("+")
    }

    /**
     * Returns the display name for the travelling mode toggle.
     * Called from Settings (Phase 4 UI).
     */
    fun isTravellingMode(ctx: Context): Boolean =
        ctx.getSharedPreferences("roaming_prefs", Context.MODE_PRIVATE)
            .getBoolean("travelling_mode", false)

    fun setTravellingMode(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences("roaming_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("travelling_mode", enabled)
            .apply()
    }
}