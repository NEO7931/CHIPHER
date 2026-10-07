package com.chipherlock.app

import android.app.Activity
import android.os.Bundle

/**
 * Required stub activity for CHIPHERLOCK to be eligible as the default SMS app.
 * Android requires a default SMS app to declare an activity that handles SENDTO
 * intents with sms/smsto/mms/mmsto schemes.
 *
 * CHIPHERLOCK is a security scanner, not a full SMS client, so this activity
 * exists solely to satisfy the OS requirement. It finishes immediately on creation.
 */
class SmsComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CHIPHERLOCK does not compose SMS messages.
        // This stub exists only to satisfy the default SMS app requirement.
        finish()
    }
}