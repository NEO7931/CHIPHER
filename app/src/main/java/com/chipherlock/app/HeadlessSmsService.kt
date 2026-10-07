package com.chipherlock.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required stub service for CHIPHERLOCK to be eligible as the default SMS app.
 * Android requires a default SMS app to declare a service that handles
 * RESPOND_VIA_MESSAGE intents (headless SMS sending, e.g. from quick reply).
 *
 * CHIPHERLOCK is a security scanner, not a full SMS client, so this service
 * exists solely to satisfy the OS requirement and does nothing.
 */
class HeadlessSmsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}