package com.chipherlock.app

// ---------------------------------------------------------------------------
// ScoreBreakdown.kt
// Holds every factor that fired during scoring so the UI can show a receipt.
// ---------------------------------------------------------------------------

data class ScoreFactor(
    val label: String,   // e.g. "\"gcash\" detected"
    val points: Int      // e.g. 20
)

data class ScoreBreakdown(
    val factors: List<ScoreFactor>,  // every triggered keyword / structural rule
    val total: Int,
    val level: String,               // "HIGH" | "MEDIUM" | "LOW"
    val category: String             // "Financial Fraud" | "Prize Scam" | …
)