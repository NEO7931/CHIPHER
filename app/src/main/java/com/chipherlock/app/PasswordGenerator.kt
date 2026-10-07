package com.chipherlock.app

object PasswordGenerator {

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS    = "0123456789"
    private const val SYMBOLS   = "!@#\$%^&*()_+-=[]{}|;:,.<>?"
    private const val AMBIGUOUS = "Il1O0"   // chars that look alike — excluded when avoidAmbiguous = true

    data class Options(
        val length         : Int     = 16,
        val useUppercase   : Boolean = true,
        val useLowercase   : Boolean = true,
        val useDigits      : Boolean = true,
        val useSymbols     : Boolean = true,
        val avoidAmbiguous : Boolean = true
    )

    /**
     * Generates a cryptographically random password matching [options].
     * Guarantees at least one character from every enabled set.
     * Returns null if no character set is enabled.
     */
    fun generate(options: Options): String? {
        val pool = buildString {
            if (options.useUppercase) append(if (options.avoidAmbiguous) UPPERCASE.filter { it !in AMBIGUOUS } else UPPERCASE)
            if (options.useLowercase) append(if (options.avoidAmbiguous) LOWERCASE.filter { it !in AMBIGUOUS } else LOWERCASE)
            if (options.useDigits)    append(if (options.avoidAmbiguous) DIGITS.filter    { it !in AMBIGUOUS } else DIGITS)
            if (options.useSymbols)   append(SYMBOLS)
        }
        if (pool.isEmpty()) return null

        val rng = java.security.SecureRandom()

        // Build a list that guarantees one char from each enabled set
        val guaranteed = mutableListOf<Char>()
        if (options.useUppercase) guaranteed.add(UPPERCASE.filter { it !in (if (options.avoidAmbiguous) AMBIGUOUS else "") }.random(rng.asKotlinRandom()))
        if (options.useLowercase) guaranteed.add(LOWERCASE.filter { it !in (if (options.avoidAmbiguous) AMBIGUOUS else "") }.random(rng.asKotlinRandom()))
        if (options.useDigits)    guaranteed.add(DIGITS.filter    { it !in (if (options.avoidAmbiguous) AMBIGUOUS else "") }.random(rng.asKotlinRandom()))
        if (options.useSymbols)   guaranteed.add(SYMBOLS.random(rng.asKotlinRandom()))

        // Fill the rest randomly from the full pool
        val rest = (1..(options.length - guaranteed.size)).map { pool[rng.nextInt(pool.length)] }

        // Shuffle so guaranteed chars aren't always at the start
        val all = (guaranteed + rest).toMutableList()
        all.shuffle(rng)

        return all.take(options.length).joinToString("")
    }

    // Extension to use SecureRandom with Kotlin's Random API
    private fun java.security.SecureRandom.asKotlinRandom(): kotlin.random.Random =
        object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int =
                this@asKotlinRandom.nextInt().ushr(32 - bitCount) and (-1 ushr (32 - bitCount))
        }
}