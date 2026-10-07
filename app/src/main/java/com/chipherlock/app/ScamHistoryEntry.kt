package com.chipherlock.app

import org.json.JSONArray
import org.json.JSONObject

data class ScamHistoryEntry(
    val sender:         String,
    val messagePreview: String,                        // was "body" — renamed to match SmsFragment
    val riskScore:      Int,
    val riskLevel:      String,
    val category:       String,
    val factors:        List<ScoreFactor> = emptyList(),
    val timestamp:      Long = System.currentTimeMillis(),
    val source:         String = ""                    // e.g. "Manual Scan", "Auto-Scan", "🗑️ Deleted"
) {
    fun toJson(): JSONObject {
        val factorsArray = JSONArray()
        for (f in factors) {
            factorsArray.put(JSONObject().apply {
                put("label",  f.label)
                put("points", f.points)
            })
        }
        return JSONObject().apply {
            put("sender",         sender)
            put("messagePreview", messagePreview)
            put("riskScore",      riskScore)
            put("riskLevel",      riskLevel)
            put("category",       category)
            put("factors",        factorsArray)
            put("timestamp",      timestamp)
            put("source",         source)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): ScamHistoryEntry {
            val factorsList  = mutableListOf<ScoreFactor>()
            val factorsArray = obj.optJSONArray("factors") ?: JSONArray()
            for (i in 0 until factorsArray.length()) {
                val f = factorsArray.getJSONObject(i)
                factorsList.add(ScoreFactor(
                    label  = f.optString("label",  "Unknown"),
                    points = f.optInt(   "points", 0)
                ))
            }
            return ScamHistoryEntry(
                sender         = obj.optString("sender",         "Unknown"),
                // graceful fallback: old records saved the field as "body"
                messagePreview = obj.optString("messagePreview",
                    obj.optString("body", "")),
                riskScore      = obj.optInt(   "riskScore",      0),
                riskLevel      = obj.optString("riskLevel",      "LOW"),
                category       = obj.optString("category",       "⚠️ Suspicious"),
                factors        = factorsList,
                timestamp      = obj.optLong(  "timestamp",      System.currentTimeMillis()),
                source         = obj.optString("source",         "")
            )
        }
    }
}