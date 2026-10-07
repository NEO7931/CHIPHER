package com.chipherlock.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

// ════════════════════════════════════════════════════════════════════════
// ContactsHelper.kt
//
// Looks up whether an SMS sender is in the user's Contacts. Used by the
// Inbox tab to tier messages into:
//   • CONTACT  — sender's number matches a saved contact
//   • VERIFIED — sender is a known-good service (GCash, BPI, Globe…)
//   • UNKNOWN  — everything else, full AI scrutiny applies
//
// Caches lookups in-memory for the lifetime of the process so we don't hit
// the Contacts provider once per inbox row. The cache is invalidated only
// when the process restarts, which is fine — contacts don't change often
// and a re-launch is a cheap reset.
//
// Gracefully degrades if READ_CONTACTS was denied: returns false for every
// number, so all senders fall through to VERIFIED/UNKNOWN tiers.
// ════════════════════════════════════════════════════════════════════════

object ContactsHelper {

    private val cache = mutableMapOf<String, Boolean>()

    /** Strip everything except digits, then keep the last 10 (PH mobile length). */
    private fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns true if [sender] matches any phone number stored in the
     * device's Contacts. Returns false if the sender is alphanumeric
     * (e.g. "GCASH"), if permission is denied, or if no match is found.
     */
    fun isContact(ctx: Context, sender: String): Boolean {
        // Alphanumeric senders like "GCASH" can never be a contact phone.
        if (sender.any { it.isLetter() }) return false

        val normalized = normalize(sender)
        if (normalized.isEmpty()) return false

        cache[normalized]?.let { return it }
        if (!hasPermission(ctx)) {
            cache[normalized] = false
            return false
        }

        val match = try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalized)
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor -> cursor.count > 0 } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        cache[normalized] = match
        return match
    }

    /** Call this if the user adds/removes a contact and the cache should refresh. */
    fun clearCache() { cache.clear() }

    /**
     * Returns the display name for [sender] if they are a saved contact,
     * or null if not found / permission denied.
     * Example: "09171234567" → "Mom" or "BPI Alerts"
     */
    fun getContactName(ctx: Context, sender: String): String? {
        if (sender.any { it.isLetter() }) return null  // alphanumeric sender — not a phone number
        val normalized = normalize(sender)
        if (normalized.isEmpty()) return null
        if (!hasPermission(ctx)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalized)
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (e: Exception) {
            null
        }
    }
}