package com.chipherlock.app

import org.json.JSONArray
import org.json.JSONObject

data class QuarantineEntry(
    val sender: String,
    val messageBody: String,
    val riskLevel: String,
    val riskScore: Int,
    val category: String,
    val timestamp: Long,
    val smsId: Long = -1L,
    // Every keyword/structural/AI factor that contributed to the score.
    // Lets the Q-Zone card show the same "Why this score?" breakdown that
    // History cards show. Defaults to empty for entries written by older
    // builds — the UI handles the empty case gracefully.
    val factors: List<ScoreFactor> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sender",      sender)
        put("messageBody", messageBody)
        put("riskLevel",   riskLevel)
        put("riskScore",   riskScore)
        put("category",    category)
        put("timestamp",   timestamp)
        put("smsId",       smsId)
        // Factors serialized as a parallel array of {label, points}
        val arr = JSONArray()
        factors.forEach { f ->
            arr.put(JSONObject().apply {
                put("label",  f.label)
                put("points", f.points)
            })
        }
        put("factors", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): QuarantineEntry {
            // Back-compat: tolerate legacy entries that never wrote `factors`.
            val factorsArr = json.optJSONArray("factors")
            val factors: List<ScoreFactor> = if (factorsArr != null) {
                (0 until factorsArr.length()).mapNotNull { i ->
                    val o = factorsArr.optJSONObject(i) ?: return@mapNotNull null
                    ScoreFactor(
                        label  = o.optString("label", ""),
                        points = o.optInt("points", 0)
                    )
                }
            } else emptyList()

            return QuarantineEntry(
                sender      = json.optString("sender",      "Unknown"),
                messageBody = json.optString("messageBody", ""),
                riskLevel   = json.optString("riskLevel",   "HIGH"),
                riskScore   = json.optInt   ("riskScore",   60),
                category    = json.optString("category",    "⚠️ Suspicious"),
                timestamp   = json.optLong  ("timestamp",   System.currentTimeMillis()),
                smsId       = json.optLong  ("smsId",       -1L),
                factors     = factors
            )
        }
    }
}