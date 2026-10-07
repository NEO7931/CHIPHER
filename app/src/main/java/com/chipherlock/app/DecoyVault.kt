package com.chipherlock.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Layer 1 — Honey Vault.
 *
 *   getRealPrefs(ctx)   → EncryptedSharedPreferences backed by the user's chosen master password
 *   getDecoyPrefs(ctx)  → second EncryptedSharedPreferences seeded with regionally-plausible fake entries
 *
 * Both are real, fully-functional, indistinguishable on disk. The selector (real vs decoy)
 * is determined by which key alias the user's input password unlocks. From the attacker's
 * perspective there is no metadata, no flag, no second file to compare against.
 */
object DecoyVault {

    private const val META_PREFS         = "sys_cfg_meta"  // shared with VaultIdHolder
    private const val KEY_DECOY_FILENAME = "dk"
    private const val KEY_DECOY_SEEDED   = "ds"
    private const val DECOY_PREFIX       = "vlt_"          // same prefix as real — indistinguishable

    private const val DECOY_KEY_ALIAS    = "_chipherlock_decoy_mk"

    /** Real vault prefs — delegates to the existing biometric-bound key. */
    fun getRealPrefs(ctx: Context): SharedPreferences {
        val masterKey = try {
            MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(true, 300)
                .build()
        } catch (_: Exception) {
            // Device has no secure lock screen — fall back to non-auth-bound key
            MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        return EncryptedSharedPreferences.create(
            ctx, VaultIdHolder.getVaultFileName(ctx), masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Decoy vault — separate filename, separate Keystore alias. */
    fun getDecoyPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx, DECOY_KEY_ALIAS)
            .setKeyGenParameterSpec(
                KeyGenParameterSpec.Builder(
                    DECOY_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Intentionally NOT user-auth bound — the decoy must open under duress
                    // without revealing it is the decoy.
                    .build()
            )
            .build()
        val prefs = EncryptedSharedPreferences.create(
            ctx, getDecoyFileName(ctx), masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        seedIfNeeded(ctx, prefs)
        return prefs
    }

    private fun getDecoyFileName(ctx: Context): String {
        val meta = ctx.applicationContext
            .getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        meta.getString(KEY_DECOY_FILENAME, null)?.let { return it }
        val name = DECOY_PREFIX + UUID.randomUUID().toString().replace("-", "")
        meta.edit().putString(KEY_DECOY_FILENAME, name).apply()
        return name
    }

    /** Populate the decoy vault on first access with regionally-plausible entries. */
    private fun seedIfNeeded(ctx: Context, prefs: SharedPreferences) {
        val meta = ctx.applicationContext
            .getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        if (meta.getBoolean(KEY_DECOY_SEEDED, false)) return

        val seedEntries = listOf(
            // service, username, password, packageName, isCanary
            Decoy("GCash",    "0917XXXXXXX",         "Tig3rL!ly2024", "com.globe.gcash.android",     true),
            Decoy("BDO",      "juan.delacruz",        "Mango@Sunset88", "com.bdo.mobilebanking",      true),
            Decoy("Shopee",   "shopee_user_2024",     "Bigl@s4Sale!",   "com.shopee.ph",              false),
            Decoy("Gmail",    "juan.dela.cruz1990",   "C0ffee&Rain!",   "com.google.android.gm",      true),
            Decoy("Facebook", "juan.dela.cruz.7",     "Manila2024!",    "com.facebook.katana",        false)
        )
        val arr = JSONArray()
        seedEntries.forEach {
            arr.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("service",     it.service)
                put("username",    it.username)
                put("password",    it.password)
                put("packageName", it.packageName)
                put("timestamp",   System.currentTimeMillis() - (Math.random() * 90L * 24 * 3600 * 1000).toLong())
                put("isCanary",    it.isCanary)
            })
        }
        prefs.edit().putString("vault_entries", arr.toString()).apply()
        meta.edit().putBoolean(KEY_DECOY_SEEDED, true).apply()
    }

    private data class Decoy(
        val service: String, val username: String, val password: String,
        val packageName: String, val isCanary: Boolean
    )
}