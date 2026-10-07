package com.chipherlock.app

// ---------------------------------------------------------------------------
// SmsSentReceiver.kt  (Phase 4.1 — Load Balance Tracker)
//
// Listens for the android.provider.Telephony.SMS_SENT broadcast that fires
// after SmsManager.sendTextMessage() / sendMultipartTextMessage() completes.
//
// This receiver's sole job: call LoadTrackerFragment.incrementSmsSent(context)
// so the Load Tracker counter stays accurate for SMS sent via CHIPHERLOCK.
//
// Registration: declared in AndroidManifest.xml (see manifest patch below).
// Note: This fires only for SMS *sent through this app* using SmsManager.
//       SMS sent from other apps are NOT counted — see tracker disclaimer.
// ---------------------------------------------------------------------------

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // resultCode is RESULT_OK (Activity.RESULT_OK = -1) on success.
        // Only increment on confirmed delivery to SmsManager — not on failure.
        if (resultCode == Activity.RESULT_OK) {
            LoadTrackerFragment.incrementSmsSent(context)
        }
        // Failed sends (RESULT_ERROR_GENERIC_FAILURE, RESULT_ERROR_NO_SERVICE,
        // RESULT_ERROR_NULL_PDU, RESULT_ERROR_RADIO_OFF) are silently ignored —
        // they don't consume the user's SMS allowance.
    }
}

// =============================================================================
// AndroidManifest.xml PATCH
// =============================================================================
//
// Add the following <receiver> block inside <application> in AndroidManifest.xml,
// alongside the existing SmsReceiver and MmsReceiver entries:
//
//     <!-- ── Outgoing SMS sent tracker (Phase 4.1 — Load Tracker) ──────── -->
//     <receiver
//         android:name=".SmsSentReceiver"
//         android:exported="false"/>
//
// NOTE: exported="false" is intentional — this receiver handles a PendingIntent
// that CHIPHERLOCK registers itself. It does not need to be visible to other apps.
//
// =============================================================================
// HOW TO WIRE IT IN CODE (wherever SmsManager.sendTextMessage is called)
// =============================================================================
//
// When sending an SMS via SmsManager, pass a PendingIntent for the sentIntent
// parameter so SmsSentReceiver gets called on completion:
//
//     val sentPI = PendingIntent.getBroadcast(
//         context,
//         0,
//         Intent("SMS_SENT_ACTION").also { it.setClass(context, SmsSentReceiver::class.java) },
//         PendingIntent.FLAG_IMMUTABLE
//     )
//     SmsManager.getDefault().sendTextMessage(
//         destinationAddress,
//         null,
//         messageBody,
//         sentPI,   // <-- wire the PendingIntent here
//         null      // deliveryIntent — not needed for tracker
//     )
//
// If CHIPHERLOCK does not currently send outgoing SMS via SmsManager
// (it is primarily an SMS scanner), the tracker count will only increment
// when the user taps "Activate Tracker" and then sends SMS from another app.
// In that case, the counter is an UNDERESTIMATE and the disclaimer in the
// tracker UI already notes this limitation.
// =============================================================================