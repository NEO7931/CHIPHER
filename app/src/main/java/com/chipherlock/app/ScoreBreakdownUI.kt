// ---------------------------------------------------------------------------
// ScoreBreakdownUI.kt
//
// Single source of truth for the "How we scored this" panel that appears on:
//   • History tab cards (buildDetailCard)
//   • Q-Zone tab cards   (buildQZoneCard)
//
// Design goal: explain the verdict in plain English first, hide the numbers
// behind an expandable "See what triggered the alert" section. The user sees
// at-a-glance whether the keyword scan and AI verification both fired, and
// can dig into the raw factor list if they want.
//
// The panel is fully self-contained (no XML) and takes only a ScoreBreakdown.
// Q-Zone reconstructs a ScoreBreakdown from its QuarantineEntry before calling.
// ---------------------------------------------------------------------------

package com.chipherlock.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object ScoreBreakdownUI {

    // Colors kept here so both cards stay visually identical
    private const val RISK_HIGH    = "#D32F2F"   // red
    private const val RISK_MEDIUM  = "#E65100"   // orange
    private const val RISK_LOW     = "#2E7D32"   // green
    private const val ACCENT_BLUE  = "#1976D2"   // header/links
    private const val TEXT_PRIMARY = "#212121"
    private const val TEXT_MUTED   = "#616161"
    private const val BG_CARD      = "#F5F5F5"
    private const val BG_ROW_SOFT  = "#FFFFFF"

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Build the simplified "How we decided" panel for a ScoreBreakdown.
     *
     * Layout (Option B — everyday tone):
     *   ┌─────────────────────────────────────┐
     *   │  RISK: HIGH               100 / 100 │
     *   │                                     │
     *   │  Detection results:                 │
     *   │  ✅ Keyword Scan                    │
     *   │     Found 12 suspicious phrases     │
     *   │  ❌ AI Double-Check                 │
     *   │     Couldn't run — used keywords    │
     *   │                                     │
     *   │  ▼ See what triggered the alert     │
     *   └─────────────────────────────────────┘
     */
    fun buildSimpleBreakdownPanel(
        context: Context,
        breakdown: ScoreBreakdown
    ): LinearLayout {
        val dp = { v: Int -> TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            context.resources.displayMetrics
        ).toInt() }

        // Classify factors into the two detection streams.
        //   AI factors are the ones SmsFragment.fuseBreakdowns emits with
        //   either "🤖 AI verdict: ..." (AI succeeded) or
        //   "⚠️ AI unavailable ..." (AI failed). Everything else is keyword.
        val aiVerdictFactor     = breakdown.factors.firstOrNull { it.label.startsWith("🤖 AI") }
        val aiUnavailableFactor = breakdown.factors.firstOrNull { it.label.startsWith("⚠️ AI") }
        val keywordFactors      = breakdown.factors.filter {
            !it.label.startsWith("🤖 AI") && !it.label.startsWith("⚠️ AI")
        }

        val aiSucceeded   = aiVerdictFactor != null
        val keywordCount  = keywordFactors.size

        // ── Outer container ────────────────────────────────────────────────
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG_CARD))
            val pad = dp(12)
            setPadding(pad, dp(10), pad, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        }

        // ── Top: RISK label + score ───────────────────────────────────────
        outer.addView(buildRiskHeader(context, breakdown, dp))

        // ── Phase 3.2: Plain-language "Why was this flagged?" explainer ───
        // Only shown for MEDIUM and HIGH — gives the user a human-readable
        // explanation of the threat type and one concrete action to take.
        // Sits above the technical detection rows so non-technical users see
        // the plain explanation first, details second.
        if (breakdown.level == "HIGH" || breakdown.level == "MEDIUM") {
            outer.addView(ScoreExplainerPanel.build(context, breakdown.category, breakdown.level))
        }

        // ── "Detection results:" label ─────────────────────────────────────
        outer.addView(TextView(context).apply {
            text = "Detection results"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_MUTED))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10); it.bottomMargin = dp(4) }
        })

        // ── Keyword Scan row ──────────────────────────────────────────────
        outer.addView(buildDetectionRow(
            context, dp,
            icon  = if (keywordCount > 0) "✅" else "⚪",
            title = "Keyword Scan",
            detail = when {
                keywordCount == 0 -> "No suspicious phrases found"
                keywordCount == 1 -> "Found 1 suspicious phrase"
                else              -> "Found $keywordCount suspicious phrases"
            }
        ))

        // ── AI Double-Check row ────────────────────────────────────────────
        val (aiIcon, aiDetail) = when {
            aiSucceeded -> {
                // Pull the AI's reason out of the label.
                // Label format: "🤖 AI verdict: HIGH — <reason>"
                val raw = aiVerdictFactor!!.label
                val reason = raw.substringAfter(" — ", "").ifBlank { raw }
                "✅" to "Confirmed: $reason"
            }
            aiUnavailableFactor != null -> {
                "❌" to "Couldn't run — used keywords only"
            }
            else -> {
                // No AI factor at all — analysis ran before fusion was added,
                // or AI was skipped (e.g. no suspicious signals to verify).
                "➖" to "Not run for this message"
            }
        }
        outer.addView(buildDetectionRow(
            context, dp,
            icon  = aiIcon,
            title = "AI Double-Check",
            detail = aiDetail
        ))

        // ── Collapsible "See what triggered the alert" ────────────────────
        if (breakdown.factors.isNotEmpty()) {
            outer.addView(buildExpandableEvidence(context, dp, breakdown, aiUnavailableFactor))
        }

        return outer
    }

    // -----------------------------------------------------------------------
    // Section builders
    // -----------------------------------------------------------------------

    private fun buildRiskHeader(
        context: Context,
        breakdown: ScoreBreakdown,
        dp: (Int) -> Int
    ): LinearLayout {
        val riskColor = when (breakdown.level) {
            "HIGH"   -> RISK_HIGH
            "MEDIUM" -> RISK_MEDIUM
            else     -> RISK_LOW
        }
        val riskLabel = when (breakdown.level) {
            "HIGH"   -> "RISK: HIGH"
            "MEDIUM" -> "RISK: MEDIUM"
            else     -> "RISK: LOW"
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(context).apply {
                text = riskLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(riskColor))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(TextView(context).apply {
                text = "${breakdown.total} / 100"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(riskColor))
            })
        }
    }

    private fun buildDetectionRow(
        context: Context,
        dp: (Int) -> Int,
        icon: String,
        title: String,
        detail: String
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(BG_ROW_SOFT))
            val pad = dp(8)
            setPadding(pad, dp(6), pad, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }

            addView(TextView(context).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(10); it.topMargin = dp(2) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(TEXT_PRIMARY))
                })
                addView(TextView(context).apply {
                    text = detail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor(TEXT_MUTED))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(2) }
                })
            })
        }
    }

    // Tappable footer that reveals the raw factor list. Hidden by default so
    // the card stays uncluttered for users who don't want the gritty details.
    private fun buildExpandableEvidence(
        context: Context,
        dp: (Int) -> Int,
        breakdown: ScoreBreakdown,
        skipFactor: ScoreFactor?
    ): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
        }

        // List body (hidden initially)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        }
        // Skip the duplicate "AI unavailable" entry — we already said so
        // in the AI Double-Check row above.
        val factors = breakdown.factors.filter { it !== skipFactor }
        for (f in factors) {
            list.addView(buildEvidenceRow(context, dp, f))
        }

        // Toggle row
        val toggle = TextView(context).apply {
            text = "▼  See what triggered the alert"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(ACCENT_BLUE))
            setPadding(0, dp(4), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (list.visibility == View.GONE) {
                    list.visibility = View.VISIBLE
                    text = "▲  Hide details"
                } else {
                    list.visibility = View.GONE
                    text = "▼  See what triggered the alert"
                }
            }
        }
        container.addView(toggle)
        container.addView(list)
        return container
    }

    private fun buildEvidenceRow(
        context: Context,
        dp: (Int) -> Int,
        factor: ScoreFactor
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(3) }
            addView(TextView(context).apply {
                text = "•  "
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor(TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(TextView(context).apply {
                text = factor.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }
    }
}