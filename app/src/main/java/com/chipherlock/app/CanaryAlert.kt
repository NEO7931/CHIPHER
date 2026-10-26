package com.chipherlock.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Layer 2 — Canary alert.
 *
 * Fired by ChipherLockAutofillService when a fill request is matched against a
 * vault entry whose isCanary flag is true. This is the moment the attacker first
 * uses a stolen credential — and the moment they become detectable.
 *
 * The current implementation surfaces a high-priority local notification. The
 * extended audit recommends adding an out-of-band channel (email or push from a
 * server) — those integrations are deliberately omitted here because they depend
 * on infrastructure outside the app. The hook is in place; back-end integration
 * is a follow-up task.
 */
object CanaryAlert {

    private const val CHANNEL_ID   = "chipherlock_canary"
    private const val CHANNEL_NAME = "Security Alerts"
    private const val NOTIF_ID     = 0xCAFE

    fun fire(ctx: Context, service: String, attackerPackage: String?) {
        ensureChannel(ctx)

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "report")     // route the user to the Report tab
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val attacker = attackerPackage ?: "unknown"
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Security alert — possible breach")
            .setContentText("Canary credential used for \"$service\" by $attacker")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "A protected canary credential for \"$service\" was just used by " +
                        "another app ($attacker). If this was not you, change your real " +
                        "passwords immediately."
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)

        // Persist the breach event so the Report tab can show it.
        ctx.getSharedPreferences("canary_events", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_breach_ts", System.currentTimeMillis())
            .putString("last_breach_service", service)
            .putString("last_breach_attacker", attacker)
            .apply()
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "CipherLock canary tripwire alerts"
            enableVibration(true)
            enableLights(true)
        })
    }
}