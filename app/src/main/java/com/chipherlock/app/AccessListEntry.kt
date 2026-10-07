package com.chipherlock.app

import org.json.JSONObject

data class AccessListEntry(
    val sender:    String,
    val type:      String,
    val reason:    String,
    val timestamp: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sender",    sender)
        put("type",      type)
        put("reason",    reason)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): AccessListEntry = AccessListEntry(
            sender    = json.optString("sender", json.optString("number", "Unknown")),
            type      = json.optString("type",   "BLOCK"),
            reason    = json.optString("reason", "Manual"),
            timestamp = json.optLong  ("timestamp", System.currentTimeMillis())
        )
    }
}