package com.chipherlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required stub receiver for CHIPHERLOCK to be eligible as the default SMS app.
 * Android requires a default SMS app to declare a receiver for WAP_PUSH_DELIVER
 * intents to handle incoming MMS messages.
 *
 * CHIPHERLOCK is a security scanner, not a full SMS client, so this receiver
 * exists solely to satisfy the OS requirement and does nothing.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // CHIPHERLOCK does not handle MMS messages.
        // This stub exists only to satisfy the default SMS app requirement.
    }
}