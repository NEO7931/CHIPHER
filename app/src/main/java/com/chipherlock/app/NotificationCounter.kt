package com.chipherlock.app

import android.content.Context
import org.json.JSONArray

object NotificationCounter {

    private const val PREFS       = "notification_prefs"
    private const val KEY_INBOX   = "unread_inbox"
    private const val KEY_SCAMS   = "unread_scams"
    private const val KEY_BANKING = "unread_banking"
    private const val KEY_QZONE   = "unread_qzone"

    fun increment(ctx: Context, category: SmsCategory) {
        val key = keyFor(category) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun markRead(ctx: Context, category: SmsCategory) {
        val key = keyFor(category) ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key, 0).apply()
    }

    fun markAllRead(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_INBOX, 0); putInt(KEY_SCAMS, 0)
            putInt(KEY_BANKING, 0); putInt(KEY_QZONE, 0)
        }.apply()
    }

    fun getCount(ctx: Context, category: SmsCategory): Int {
        val key = keyFor(category) ?: return 0
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, 0)
    }

    fun getBellTotal(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt(KEY_INBOX, 0) + p.getInt(KEY_SCAMS, 0) +
                p.getInt(KEY_BANKING, 0) + p.getInt(KEY_QZONE, 0)
    }

    fun recomputeFromSource(ctx: Context) {
        val scamsCount = try {
            val arr = JSONArray(
                ctx.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                    .getString("scam_history_json", "[]") ?: "[]"
            )
            var unread = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lvl = obj.optString("riskLevel", "LOW")
                if (!obj.optBoolean("read", false) && (lvl == "MEDIUM" || lvl == "HIGH")) unread++
            }
            unread
        } catch (e: Exception) { 0 }

        val qzoneCount = try {
            JSONArray(
                ctx.getSharedPreferences("quarantine_prefs", Context.MODE_PRIVATE)
                    .getString("quarantine_json", "[]") ?: "[]"
            ).length()
        } catch (e: Exception) { 0 }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_SCAMS, scamsCount); putInt(KEY_QZONE, qzoneCount)
        }.apply()
    }

    private fun keyFor(category: SmsCategory): String? = when (category) {
        SmsCategory.INBOX   -> KEY_INBOX
        SmsCategory.SCAMS   -> KEY_SCAMS
        SmsCategory.BANKING -> KEY_BANKING
        SmsCategory.QZONE   -> KEY_QZONE
        else -> null
    }
}