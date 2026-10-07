package com.chipherlock.app

import android.app.AlertDialog
import android.content.Context
import android.net.Uri

/**
 * Handles safe export before uninstall:
 *
 * - Feature 1: Existing SMS are never touched by CHIPHERLOCK during normal
 *   operation. This is guaranteed by design — the app only deletes messages
 *   when the user explicitly taps delete or triggers Safe Export.
 *
 * - Feature 2: Safe Export deletes all HIGH/MEDIUM risk messages from the
 *   phone's SMS provider and clears all CHIPHERLOCK app data. Clean (LOW)
 *   messages remain untouched and accessible in any SMS app after uninstall.
 */
object SafeExportManager {

    // ── Count helpers ────────────────────────────────────────────────────

    fun countScamMessages(ctx: Context): Int {
        val qZone   = SmsFragment.loadQuarantine(ctx).size
        val history = SmsFragment.loadHistory(ctx)
            .count { it.riskLevel == "HIGH" || it.riskLevel == "MEDIUM" }
        return qZone + history
    }

    fun countTotalSms(ctx: Context): Int = try {
        ctx.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("_id"), null, null, null
        )?.use { it.count } ?: 0
    } catch (e: Exception) { 0 }

    // ── Delete scam messages from content://sms ──────────────────────────

    /**
     * Deletes all HIGH/MEDIUM risk messages from the SMS provider.
     * Requires CHIPHERLOCK to be the default SMS app (otherwise returns 0).
     * Uses smsId for Q-Zone entries, sender+body prefix for History-only ones.
     */
    fun deleteScamMessages(ctx: Context): Int {
        var deleted = 0

        // Q-Zone entries — most reliable since we store the actual smsId
        val quarantine = SmsFragment.loadQuarantine(ctx)
        val quarantineKeys = mutableSetOf<String>()

        for (entry in quarantine) {
            quarantineKeys.add(entry.sender.trim() + "|" + entry.messageBody.take(60))
            try {
                if (entry.smsId != -1L) {
                    val rows = ctx.contentResolver.delete(
                        Uri.parse("content://sms/${entry.smsId}"), null, null
                    )
                    if (rows > 0) deleted++
                } else {
                    // No smsId — match by sender + body prefix
                    val rows = ctx.contentResolver.delete(
                        Uri.parse("content://sms"),
                        "address = ? AND body LIKE ?",
                        arrayOf(entry.sender, entry.messageBody.take(60) + "%")
                    )
                    deleted += rows
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // History-only entries not already covered by Q-Zone
        val history = SmsFragment.loadHistory(ctx)
            .filter { it.riskLevel == "HIGH" || it.riskLevel == "MEDIUM" }

        for (entry in history) {
            val key = entry.sender.trim() + "|" + entry.messagePreview.take(60)
            if (key in quarantineKeys) continue  // already handled above
            try {
                val rows = ctx.contentResolver.delete(
                    Uri.parse("content://sms"),
                    "address = ? AND body LIKE ?",
                    arrayOf(entry.sender, entry.messagePreview.take(60) + "%")
                )
                deleted += rows
            } catch (e: Exception) { e.printStackTrace() }
        }

        return deleted
    }

    // ── Clear all CHIPHERLOCK-specific app data ───────────────────────────

    /**
     * Wipes all SharedPreferences created by CHIPHERLOCK.
     * The SMS messages in content://sms are NOT affected by this call.
     */
    fun clearAppData(ctx: Context) {
        listOf(
            "quarantine_prefs",
            "scam_history_prefs",
            "access_list_prefs",
            "blacklist_prefs",
            "notification_prefs",
            "scam_alert_prefs",
            "scam_prefs",
            "low_cache_prefs",
            "risk_index_prefs",
            "soft_delete_prefs",
            "chipherlock_settings",
            "first_launch_prefs"
        ).forEach { name ->
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    // ── Safe Export dialog ────────────────────────────────────────────────

    fun showSafeExportDialog(ctx: Context, isDefaultSmsApp: Boolean) {
        val scamCount  = countScamMessages(ctx)
        val totalSms   = countTotalSms(ctx)
        val cleanCount = (totalSms - scamCount).coerceAtLeast(0)

        val message = buildString {
            append("📊 What will happen:\n\n")
            append("  🔴 $scamCount scam message(s) → permanently deleted\n")
            append("  🟢 $cleanCount clean message(s) → preserved on device\n\n")
            if (!isDefaultSmsApp) {
                append("⚠️ Set CHIPHERLOCK as your default SMS app first\n")
                append("to delete scam messages from the phone.\n\n")
                append("App data (Q-Zone, history, access list) will still\n")
                append("be cleared regardless.\n\n")
            }
            append("After this, you can safely uninstall CHIPHERLOCK.\n")
            append("All clean messages will be accessible in any SMS app.")
        }

        AlertDialog.Builder(ctx)
            .setTitle("🧹 Safe Export")
            .setMessage(message)
            .setPositiveButton("Proceed") { _, _ ->
                val deleted = if (isDefaultSmsApp) deleteScamMessages(ctx) else 0
                clearAppData(ctx)

                val result = buildString {
                    if (isDefaultSmsApp) {
                        append("✅ $deleted scam message(s) deleted from device.\n")
                    } else {
                        append("⚠️ Scam messages NOT deleted from device\n")
                        append("(CHIPHERLOCK is not the default SMS app).\n")
                    }
                    append("✅ App data cleared.\n")
                    append("✅ $cleanCount clean message(s) preserved.\n\n")
                    append("You can now safely uninstall CHIPHERLOCK.")
                }

                AlertDialog.Builder(ctx)
                    .setTitle("✅ Export Complete")
                    .setMessage(result)
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── First-launch preservation notice ─────────────────────────────────

    /**
     * Shows a one-time notice when the user first sets CHIPHERLOCK as default.
     * Reassures them that existing messages are not touched.
     */
    fun showPreservationNoticeIfNeeded(ctx: Context, isDefaultSmsApp: Boolean) {
        if (!isDefaultSmsApp) return
        val prefs = ctx.getSharedPreferences("first_launch_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("preservation_notice_shown", false)) return
        prefs.edit().putBoolean("preservation_notice_shown", true).apply()

        AlertDialog.Builder(ctx)
            .setTitle("✅ Your Messages Are Safe")
            .setMessage(
                "CHIPHERLOCK is now your default SMS app.\n\n" +
                        "All your existing messages are preserved and untouched. " +
                        "CHIPHERLOCK only reads your SMS to protect you — it never " +
                        "deletes any message without your explicit action.\n\n" +
                        "When you're ready to uninstall, use the 🧹 Safe Export " +
                        "option in the drawer to clean up scam messages first."
            )
            .setPositiveButton("Got it", null)
            .show()
    }
}