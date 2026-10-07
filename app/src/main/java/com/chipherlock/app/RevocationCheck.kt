package com.chipherlock.app

import android.content.Context
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CertificatePinner

object RevocationCheck {

    private const val PREFS_NAME       = "rev_state"
    private const val KEY_LAST_OK      = "rev_lastok"
    private const val KEY_REMOTE_LOCK  = "rev_locked"
    private const val OFFLINE_GRACE_MS = 24L * 3600 * 1000

    private const val REVOCATION_URL =
        "https://gist.githubusercontent.com/NEO7931/ca8812045ee3fcb913f053e6bda995b1/raw/cipherlock_revocation.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("gist.githubusercontent.com",
                        "sha256/W+jBdq3o4qj8cXXBURwKqofJk8BG59NEPXOEgMh53sA=")
                    .build()
            )
            .build()
    }

    // ── Lock-state queries ───────────────────────────────────────────────────

    fun isRemotelyLocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_REMOTE_LOCK, false)

    fun clearRemoteLock(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_REMOTE_LOCK, false).apply()
    }

    // ── Background check — never blocks the unlock path ──────────────────────

    fun checkInBackground(ctx: Context) {
        Thread {
            try {
                val p       = prefs(ctx)
                val elapsed = System.currentTimeMillis() - p.getLong(KEY_LAST_OK, 0L)
                if (elapsed < OFFLINE_GRACE_MS) return@Thread

                when (fetchSignal()) {
                    Signal.REVOKED ->
                        p.edit().putBoolean(KEY_REMOTE_LOCK, true).apply()
                    Signal.OK ->
                        p.edit()
                            .putLong(KEY_LAST_OK, System.currentTimeMillis())
                            .putBoolean(KEY_REMOTE_LOCK, false)
                            .apply()
                }
            } catch (_: Exception) {
                // Network unavailable / timeout / cert mismatch — do nothing.
                // Vault access is unaffected. An attacker who blocks the server
                // achieves nothing because there is no wipe triggered by this path.
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── User-initiated actions ───────────────────────────────────────────────

    // Mark device as lost. Sets the lock flag; does NOT wipe any data.
    // Must only be called after re-authentication + explicit user confirmation.
    fun userInitiatedLock(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_REMOTE_LOCK, true).apply()
        VaultAuthManager.markLocked()
    }

    // Permanent erase — the only code path that calls VaultKeyManager.wipe().
    // Must only be called after re-authentication + explicit user confirmation.
    fun userInitiatedWipe(ctx: Context) {
        VaultKeyManager.wipe(ctx)
        prefs(ctx).edit().clear().apply()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private enum class Signal { OK, REVOKED }

    private fun fetchSignal(): Signal {
        val body = client.newCall(
            Request.Builder()
                .url(REVOCATION_URL)
                .header("Cache-Control", "no-cache")
                .build()
        ).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            res.body?.string() ?: throw java.io.IOException("Empty body")
        }
        val status = try {
            org.json.JSONObject(body).getString("status")
        } catch (_: Exception) {
            throw java.io.IOException("Malformed response")
        }
        return if (status.trim().lowercase() == "revoked") Signal.REVOKED else Signal.OK
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}