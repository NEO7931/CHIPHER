package com.chipherlock.app

object PasswordStrengthAnalyzer {

    enum class Strength(val label: String, val colorHex: String) {
        WEAK("Weak", "#F44336"),
        FAIR("Fair", "#FF9800"),
        STRONG("Strong", "#4CAF50"),
        VERY_STRONG("Very Strong", "#1976D2")
    }

    data class Result(
        val strength: Strength,
        val score: Int,                  // 0–100
        val positives: List<String>,     // what the password does well
        val suggestions: List<String>    // what to improve
    )

    // Most-breached passwords — reject immediately
    private val COMMON_PASSWORDS = setOf(
        "password", "123456", "12345678", "password123", "admin", "letmein",
        "qwerty", "abc123", "monkey", "1234567890", "iloveyou", "sunshine",
        "princess", "welcome", "shadow", "dragon", "master", "666666",
        "passw0rd", "superman", "batman", "qwertyuiop", "111111", "000000"
    )

    fun analyze(password: String): Result {
        if (password.isEmpty()) {
            return Result(Strength.WEAK, 0, emptyList(), listOf("Enter a password to check its strength"))
        }

        val positives   = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var score = 0

        // ── Length ────────────────────────────────────────────────────────────
        when {
            password.length >= 20 -> { score += 35; positives.add("Excellent length (${password.length} chars)") }
            password.length >= 16 -> { score += 28; positives.add("Great length (${password.length} chars)") }
            password.length >= 12 -> { score += 20; positives.add("Good length (${password.length} chars)") }
            password.length >= 8  -> { score += 10; suggestions.add("Aim for at least 12 characters") }
            else                  -> {               suggestions.add("Too short — use at least 8 characters") }
        }

        // ── Character variety ─────────────────────────────────────────────────
        val hasUpper   = password.any { it.isUpperCase() }
        val hasLower   = password.any { it.isLowerCase() }
        val hasDigit   = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        if (hasUpper) { score += 15; positives.add("Has uppercase letters") }
        else            suggestions.add("Add uppercase letters (A–Z)")

        if (hasLower) { score += 10; positives.add("Has lowercase letters") }
        else            suggestions.add("Add lowercase letters (a–z)")

        if (hasDigit) { score += 15; positives.add("Contains numbers") }
        else            suggestions.add("Add at least one number (0–9)")

        if (hasSpecial) { score += 20; positives.add("Contains special characters") }
        else              suggestions.add("Add special characters (e.g. !@#\$%^&*)")

        // ── Variety bonus — using 3+ character types ──────────────────────────
        val typesUsed = listOf(hasUpper, hasLower, hasDigit, hasSpecial).count { it }
        if (typesUsed >= 4) { score += 5; positives.add("Uses all character types") }

        // ── Common password penalty ───────────────────────────────────────────
        if (COMMON_PASSWORDS.contains(password.lowercase())) {
            score = minOf(score, 8)
            suggestions.add("This is one of the most-guessed passwords — choose something unique")
        }

        // ── Sequential / repeated characters ─────────────────────────────────
        val hasTripleRepeat = password.windowed(3).any { w -> w.all { it == w[0] } }
        if (hasTripleRepeat) {
            score -= 10
            suggestions.add("Avoid repeating the same character 3+ times in a row")
        }

        val hasSequential = listOf("abcdef", "qwerty", "123456", "654321")
            .any { seq -> password.lowercase().contains(seq) }
        if (hasSequential) {
            score -= 10
            suggestions.add("Avoid keyboard sequences like 'qwerty' or '123456'")
        }

        score = score.coerceIn(0, 100)

        val strength = when {
            score >= 80 -> Strength.VERY_STRONG
            score >= 55 -> Strength.STRONG
            score >= 30 -> Strength.FAIR
            else        -> Strength.WEAK
        }

        return Result(strength, score, positives, suggestions)
    }
}