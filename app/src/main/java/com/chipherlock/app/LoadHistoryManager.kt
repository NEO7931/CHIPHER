package com.chipherlock.app

// ---------------------------------------------------------------------------
// LoadHistoryManager.kt  (Phase 4.1 — Security Layer)
//
// Secure storage for SMS spend history.
//
// Security decisions:
//   • Encrypted with AES-256-GCM using the same DEK as the Vault
//   • Recipient numbers are SHA-256 hashed before storage — never stored raw
//   • USSD responses are NEVER stored — display only
//   • Auto-purge: entries older than 30 days are deleted on every load
//   • Secure delete: 3-pass overwrite on manual clear
//   • Requires vault to be unlocked — history is inaccessible without auth
// ---------------------------------------------------------------------------

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object LoadHistoryManager {

    private const val HISTORY_FILE   = "load_history.enc"
    private const val RETENTION_DAYS = 30L
    private const val MAX_ENTRIES    = 500

    // ── Data model ───────────────────────────────────────────────────────────

    data class SmsSpendEntry(
        val timestampMs:   Long,
        val recipientHash: String,   // SHA-256 hash — never the raw number
        val promoId:       String,
        val promoName:     String,
        val costPesos:     Double,   // 0.0 = unlimited promo, 1.0 = regular load
        val telcoId:       String
    )

    // ── Recipient hashing ────────────────────────────────────────────────────

    fun hashRecipient(number: String): String {
        val clean  = number.trim().replace(" ", "").replace("-", "")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash   = digest.digest(clean.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP).take(16)
    }

    // ── Write ────────────────────────────────────────────────────────────────

    fun appendEntry(ctx: Context, entry: SmsSpendEntry) {
        if (!VaultKeyManager.isUnlocked()) return
        val existing = loadRaw(ctx).toMutableList()
        existing.add(0, entry)
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val pruned = existing.filter { it.timestampMs >= cutoff }.take(MAX_ENTRIES)
        saveRaw(ctx, pruned)
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    fun loadHistory(ctx: Context): List<SmsSpendEntry> {
        if (!VaultKeyManager.isUnlocked()) return emptyList()
        val all    = loadRaw(ctx)
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val fresh  = all.filter { it.timestampMs >= cutoff }
        if (fresh.size < all.size) saveRaw(ctx, fresh)
        return fresh
    }

    fun todaySummary(ctx: Context): Pair<Int, Double> {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val today      = loadHistory(ctx).filter { it.timestampMs >= startOfDay }
        return Pair(today.size, today.sumOf { it.costPesos })
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    fun clearHistory(ctx: Context) {
        val f = File(ctx.filesDir, HISTORY_FILE)
        if (f.exists()) secureDelete(f)
    }

    fun hasHistory(ctx: Context): Boolean =
        File(ctx.filesDir, HISTORY_FILE).exists()

    // ── Private: serialisation ───────────────────────────────────────────────

    private fun saveRaw(ctx: Context, entries: List<SmsSpendEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("ts",    e.timestampMs)
                put("rh",    e.recipientHash)
                put("pid",   e.promoId)
                put("pname", e.promoName)
                put("cost",  e.costPesos)
                put("telco", e.telcoId)
            })
        }
        try { VaultKeyManager.saveLoadHistory(arr.toString(), ctx) }
        catch (_: IllegalStateException) { /* vault locked mid-write — discard */ }
    }

    private fun loadRaw(ctx: Context): List<SmsSpendEntry> {
        return try {
            val json = VaultKeyManager.loadLoadHistory(ctx) ?: return emptyList()
            val arr  = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SmsSpendEntry(
                    timestampMs   = o.optLong("ts", 0L),
                    recipientHash = o.optString("rh", ""),
                    promoId       = o.optString("pid", "none"),
                    promoName     = o.optString("pname", "No promo"),
                    costPesos     = o.optDouble("cost", 1.0),
                    telcoId       = o.optString("telco", "UNKNOWN")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── Private: secure delete ───────────────────────────────────────────────

    private fun secureDelete(f: File) {
        val rng = java.security.SecureRandom()
        val buf = ByteArray(8192)
        val len = f.length()
        repeat(3) {
            f.outputStream().use { os ->
                var written = 0L
                while (written < len) {
                    rng.nextBytes(buf)
                    val n = minOf(buf.size.toLong(), len - written).toInt()
                    os.write(buf, 0, n)
                    written += n
                }
                os.fd.sync()
            }
        }
        f.delete()
    }
}