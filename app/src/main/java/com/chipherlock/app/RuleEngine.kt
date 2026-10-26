package com.chipherlock.app

import android.content.Context
import org.json.JSONArray

data class RuleMatch(
    val ruleId:      String,
    val description: String,
    val weight:      Int,
    val category:    String
)

object RuleEngine {

    private val rules = mutableListOf<Triple<String, Regex, RuleMatch>>()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        try {
            val json = ctx.assets.open("rules_v1.json").bufferedReader().readText()
            val arr  = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj     = arr.optJSONObject(i) ?: continue
                val id      = obj.optString("id",          "")
                val pattern = obj.optString("pattern",     "").takeIf { it.isNotEmpty() } ?: continue
                val weight  = obj.optInt   ("weight",      10)
                val cat     = obj.optString("category",    "Suspicious")
                val desc    = obj.optString("description", pattern)
                try {
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    rules.add(Triple(id, regex, RuleMatch(id, desc, weight, cat)))
                } catch (e: Exception) {
                    // Invalid regex in rule — skip safely
                }
            }
            loaded = true
        } catch (e: Exception) {
            // Missing or malformed rules_v1.json — keyword scan still runs
            e.printStackTrace()
        }
    }

    fun evaluate(body: String): List<RuleMatch> {
        if (!loaded || rules.isEmpty()) return emptyList()
        val lower = body.lowercase()
        return rules.mapNotNull { (_, regex, match) ->
            if (regex.containsMatchIn(lower)) match else null
        }
    }
}