package com.chipherlock.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * REM-008 / Layer 4 — vault filename indistinguishability.
 *
 * Stores a per-install random UUID that becomes the SharedPreferences file name
 * for the encrypted vault. The UUID is itself stored in a separate, deliberately
 * non-obvious SharedPreferences file ("sys_cfg_meta") so a forensic enumeration
 * does not find a single labelled "vault" artifact.
 *
 * Concurrency: the first-call UUID generation is double-checked under a lock so
 * multiple Fragment instances created on the same launch all agree on one ID.
 */
object VaultIdHolder {

    private const val META_PREFS    = "sys_cfg_meta"
    private const val KEY_VAULT_UID = "vk"
    private const val PREFIX        = "vlt_"          // appears as vlt_<32 hex>.xml on disk

    @Volatile private var cached: String? = null
    private val lock = Any()

    /** Returns the per-install vault filename. Generated on first call. */
    fun getVaultFileName(ctx: Context): String {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val prefs = meta(ctx)
            val existing = prefs.getString(KEY_VAULT_UID, null)
            val uid = existing ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString(KEY_VAULT_UID, it).apply()
            }
            val name = "$PREFIX$uid"
            cached = name
            return name
        }
    }

    /** Reset — for tests or for the kill-switch wipe in Layer 5. */
    fun resetForWipe(ctx: Context) {
        meta(ctx).edit().clear().apply()
        cached = null
    }

    private fun meta(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
}