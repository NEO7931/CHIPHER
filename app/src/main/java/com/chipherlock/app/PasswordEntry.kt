package com.chipherlock.app

import java.util.Arrays
import java.util.UUID

/**
 * Vault entry — REM-001 / Layer 2.
 *
 * `password` is a CharArray so it can be explicitly zeroed with [wipe] after use,
 * avoiding the immutable-String heap-residency problem described in VULN-001.
 *
 * NOTE: a data class with a CharArray member generates broken equals()/hashCode()
 * (Array.equals is reference-equal). We override them to compare by id, which is
 * unique per entry. If you ever need content equality, use password.contentEquals().
 */
class PasswordEntry(
    val id: String = UUID.randomUUID().toString(),
    val service: String,
    val username: String,
    val password: CharArray,
    val packageName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isCanary: Boolean = false      // Layer 2 — honeytoken flag
) {
    /** Zero out the password buffer. Safe to call multiple times. */
    fun wipe() {
        Arrays.fill(password, '\u0000')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasswordEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}