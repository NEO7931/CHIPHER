package com.chipherlock.app

import org.json.JSONObject

data class BlacklistEntry(
    val sender: String,
    val reason: String,    // "Manual" or "Auto-Scam"
    val timestamp: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sender",    sender)
        put("reason",    reason)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): BlacklistEntry = BlacklistEntry(
            sender    = json.optString("sender",    "Unknown"),
            reason    = json.optString("reason",    "Manual"),
            timestamp = json.optLong  ("timestamp", System.currentTimeMillis())
        )
    }
}