package com.chipherlock.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Layer 3 — progressive lockout counter, HMAC-signed.
 *
 * Stores the failure count in a SharedPreferences file under META_PREFS, signed
 * with an HMAC-SHA-256 key generated in the Android Keystore. Tampering with the
 * count from outside the app yields an invalid signature; LockoutCounter then
 * treats the file as compromised and forces a 24-hour lockout.
 *
 * Argon2id integration: the cost factor [argon2MemoryKb] is computed from the
 * current failure count. Doubles every 3 failures, ceiling at 1 GB.
 */
object LockoutCounter {

    private const val PREFS_NAME      = "sys_cfg_meta"   // shared with VaultIdHolder
    private const val KEY_FAIL_COUNT  = "lc_n"
    private const val KEY_FAIL_SIG    = "lc_sig"
    private const val KEY_LAST_FAIL   = "lc_t"
    private const val KEY_HARD_LOCK   = "lc_hl"

    private const val KEYSTORE_ALIAS  = "chipherlock_lockout_hmac"
    private const val KEYSTORE_TYPE   = "AndroidKeyStore"
    private const val HMAC_ALG        = KeyProperties.KEY_ALGORITHM_HMAC_SHA256

    private const val COOLDOWN_AFTER  = 5        // failures
    private const val HARD_LOCK_AFTER = 10       // failures → 24 h
    private const val HARD_LOCK_MS    = 24L * 3600 * 1000
    // Cooldown ramp: 30 s after attempt 5, doubling per attempt up to 5 minutes.
    private val COOLDOWN_SCHEDULE_MS  = longArrayOf(30_000, 60_000, 120_000, 240_000, 300_000)

    // ── Public API ──────────────────────────────────────────────────────

    fun recordFailure(ctx: Context) {
        val prefs = prefs(ctx)
        val n = (currentValidCount(ctx) ?: -1) + 1   // -1 → treat tampered as 0 then +1
        val sig = sign(ctx, n)
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, n)
            .putString(KEY_FAIL_SIG, sig)
            .putLong(KEY_LAST_FAIL, System.currentTimeMillis())
            .apply()
        if (n >= HARD_LOCK_AFTER) {
            prefs.edit().putLong(KEY_HARD_LOCK, System.currentTimeMillis() + HARD_LOCK_MS).apply()
        }
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_FAIL_COUNT)
            .remove(KEY_FAIL_SIG)
            .remove(KEY_LAST_FAIL)
            .remove(KEY_HARD_LOCK)
            .apply()
    }

    /** Milliseconds remaining on the active cooldown. 0 if no cooldown is active. */
    fun cooldownRemainingMs(ctx: Context): Long {
        val prefs = prefs(ctx)
        val hardLockExpiry = prefs.getLong(KEY_HARD_LOCK, 0L)
        if (hardLockExpiry > System.currentTimeMillis()) {
            return hardLockExpiry - System.currentTimeMillis()
        }
        val n = currentValidCount(ctx) ?: return HARD_LOCK_MS   // tampered: hard lock
        if (n < COOLDOWN_AFTER) return 0L
        val idx = (n - COOLDOWN_AFTER).coerceAtMost(COOLDOWN_SCHEDULE_MS.size - 1)
        val cooldown = COOLDOWN_SCHEDULE_MS[idx]
        val lastFail = prefs.getLong(KEY_LAST_FAIL, 0L)
        val unlockAt = lastFail + cooldown
        return (unlockAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Current Argon2id memory cost in KiB. Used when deriving a master-password KDF. */
    fun argon2MemoryKb(ctx: Context): Int {
        val n = currentValidCount(ctx) ?: 0
        val doublings = n / 3
        val base = 64L * 1024     // 64 MiB
        val scaled = base shl doublings.coerceAtMost(14)   // hard ceiling
        return scaled.coerceAtMost(1024L * 1024).toInt()   // ≤ 1 GiB
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the count if its signature checks out; null if tampered or missing. */
    private fun currentValidCount(ctx: Context): Int? {
        val p = prefs(ctx)
        if (!p.contains(KEY_FAIL_COUNT)) return 0
        val n = p.getInt(KEY_FAIL_COUNT, 0)
        val sig = p.getString(KEY_FAIL_SIG, null) ?: return null
        return if (sig == sign(ctx, n)) n else null
    }

    private fun sign(ctx: Context, n: Int): String {
        val key = getOrCreateHmacKey()
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(key)
        val bytes = mac.doFinal(n.toString().toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun getOrCreateHmacKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        ks.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val kg = KeyGenerator.getInstance(HMAC_ALG, KEYSTORE_TYPE)
        kg.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                // No user-auth required — the counter must update on failed attempts,
                // when the user is by definition not authenticated.
                .build()
        )
        return kg.generateKey()
    }
}