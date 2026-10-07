package com.chipherlock.app

// ═══════════════════════════════════════════════════════════════════════════
// ScoreExplainerPanel.kt — Phase 3.2
//
// Adds a plain-language "Why was this flagged?" explanation above the
// existing ScoreBreakdownUI detection rows.
//
// Design goals:
//   1. Non-technical Filipino users understand why the message is dangerous
//   2. Specific — tied to the actual scam category detected, not generic
//   3. Actionable — ends with exactly one thing the user should do
//   4. Brief — max 3 sentences. The breakdown panel below handles detail.
//
// Called from ScoreBreakdownUI.buildSimpleBreakdownPanel() — injected
// between the risk header and the "Detection results:" label.
//
// Scam categories come from GroqScamAnalyzer and the RuleEngine.
// The category string may be the AI's label or a keyword-rule label.
// Matching is case-insensitive substring — broad enough to catch variations.
// ═══════════════════════════════════════════════════════════════════════════

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

object ScoreExplainerPanel {

    // ── Explanation data ──────────────────────────────────────────────────────

    data class Explanation(
        val headline: String,   // bold first line — what the threat is
        val detail: String,     // one sentence explaining the mechanism
        val action: String      // one concrete thing to do right now
    )

    // Maps category keywords → plain-language explanation.
    // Checked in order — first match wins.
    // Keywords are lowercase substrings of the category field.
    private val explanations: List<Pair<String, Explanation>> = listOf(

        "financial fraud" to Explanation(
            headline = "🏦 Possible bank or e-wallet scam",
            detail   = "This message is pretending to be from your bank or GCash to steal your OTP or password. Real banks and GCash will NEVER ask for your OTP over SMS.",
            action   = "Do not click any link and do not reply. If you're worried, call your bank directly using the number on the back of your card."
        ),

        "otp" to Explanation(
            headline = "🔑 OTP harvesting attempt",
            detail   = "Someone is trying to get your one-time password. If you share your OTP, they can log into your account immediately and transfer your money.",
            action   = "Never share your OTP with anyone — not even someone claiming to be from your bank."
        ),

        "phishing" to Explanation(
            headline = "🎣 Phishing link detected",
            detail   = "This message contains a link that leads to a fake website designed to steal your login credentials. The URL may look real but is slightly different from the official one.",
            action   = "Do not tap the link. Go directly to the official app instead."
        ),

        "prize" to Explanation(
            headline = "🎰 Fake prize or lottery scam",
            detail   = "You did not win anything. Scammers use fake prize offers to get you to pay a 'processing fee' or share your personal information.",
            action   = "Ignore this message completely. There is no prize."
        ),

        "lottery" to Explanation(
            headline = "🎰 Fake lottery scam",
            detail   = "Legitimate lotteries never notify winners by SMS, and they never ask for payment to claim a prize.",
            action   = "Ignore this message. Do not pay any fee or share any information."
        ),

        "government" to Explanation(
            headline = "🏛️ Government agency impersonation",
            detail   = "This message is pretending to be from SSS, PhilHealth, DSWD, NBI, BIR, or another government agency. Real government agencies do not send unsolicited SMS asking for personal information or payment.",
            action   = "Call the agency directly using numbers from their official website — not the number in this SMS."
        ),

        "job" to Explanation(
            headline = "💼 Fake job or task scam",
            detail   = "Legitimate employers do not recruit by mass SMS or offer money just for liking posts. These scams eventually ask for a deposit or your bank details.",
            action   = "Do not respond. Real jobs are listed on official job boards, not random SMS."
        ),

        "parcel" to Explanation(
            headline = "📦 Fake delivery or parcel scam",
            detail   = "Real couriers like LBC, J&T, and Ninjavan do not ask for payment via SMS link to release your package. This link leads to a fake payment page.",
            action   = "Track your package directly in the official courier app, not through this link."
        ),

        "delivery" to Explanation(
            headline = "📦 Fake delivery notification",
            detail   = "Scammers use fake delivery SMS to get you to click a phishing link or pay a fake fee.",
            action   = "Check your package status directly in the courier's official app."
        ),

        "romance" to Explanation(
            headline = "💑 Romance scam warning",
            detail   = "Someone you have not met in person is building a relationship online before eventually asking for money, usually through a fake emergency or investment opportunity.",
            action   = "Never send money to someone you have only met online, no matter how close you feel."
        ),

        "loan" to Explanation(
            headline = "💰 Fake loan offer",
            detail   = "Legitimate lending companies are registered with the SEC and do not recruit borrowers by unsolicited SMS. These scams steal your personal information or charge upfront fees.",
            action   = "Check if the lender is SEC-registered at sec.gov.ph before responding to any loan offer."
        ),

        "investment" to Explanation(
            headline = "📈 Investment scam (pig butchering)",
            detail   = "This type of scam shows you fake profits to gain trust, then asks for larger deposits before disappearing with your money.",
            action   = "Never invest through a platform recommended by someone you met online. Verify with the SEC first."
        ),

        "crypto" to Explanation(
            headline = "💱 Crypto investment scam",
            detail   = "Fake crypto platforms show guaranteed returns that are completely fabricated. Once you deposit enough, they block your account and vanish.",
            action   = "No legitimate investment guarantees returns. Do not send money."
        ),

        "sextortion" to Explanation(
            headline = "🔒 Sextortion threat",
            detail   = "This is a blackmail attempt. In most cases the criminal does not actually have any compromising material — this is a bluff designed to make you pay out of fear.",
            action   = "Do not pay. Report this to the PNP Anti-Cybercrime Group at acg.pnp.gov.ph."
        ),

        "extortion" to Explanation(
            headline = "🔪 Extortion or kidnapping threat",
            detail   = "These threats are almost always fake and mass-sent to random numbers. Criminals rely on fear to get quick payment before you can verify anything.",
            action   = "Stay calm. Call the person they are threatening to harm directly. Report to PNP at 911."
        ),

        "emergency" to Explanation(
            headline = "🆘 Fake emergency scam",
            detail   = "Scammers pretend to be a family member or friend in an emergency to pressure you into sending GCash immediately without thinking.",
            action   = "Call the person directly before sending any money. This is almost always fake."
        ),

        "sim swap" to Explanation(
            headline = "📲 SIM swap attempt",
            detail   = "Someone is trying to transfer your phone number to a SIM card they own so they can receive your OTPs and access your bank and GCash accounts.",
            action   = "Do not share any personal information. Contact Globe or Smart immediately if your SIM suddenly loses signal."
        ),

        "marketplace" to Explanation(
            headline = "🛒 Online marketplace fraud",
            detail   = "This message is related to a fake seller or buyer on Shopee, Lazada, or Facebook Marketplace. Common tactics include fake escrow links and overpayment schemes.",
            action   = "Only transact through the official app's payment system. Never pay outside the platform."
        ),

        "utility" to Explanation(
            headline = "⚡ Fake utility bill threat",
            detail   = "MERALCO, Maynilad, and Manila Water do not cut service with only a text message warning, and they do not accept payment through GCash links sent via SMS.",
            action   = "Pay your bill directly through the official app or accredited payment centers."
        ),

        "data" to Explanation(
            headline = "📋 Data harvesting attempt",
            detail   = "This message is trying to collect your personal information — name, address, birthday, or ID numbers — which can be used to open fraudulent accounts in your name.",
            action   = "Never give personal information in response to an unsolicited SMS."
        ),

        "suspicious link" to Explanation(
            headline = "🔗 Suspicious link detected",
            detail   = "This message contains a link that does not match the sender's claimed identity. Clicking it could install malware or lead to a fake login page.",
            action   = "Do not tap the link. If the message seems important, verify by calling the sender directly."
        ),

        "spam" to Explanation(
            headline = "📢 Unsolicited promotional message",
            detail   = "This appears to be unwanted promotional or spam content. While not always dangerous, it may be a precursor to a more targeted scam.",
            action   = "You can safely ignore or block this sender."
        )
    )

    // Default when the category doesn't match any known pattern
    private val defaultExplanation = Explanation(
        headline = "⚠️ Suspicious message detected",
        detail   = "CHIPHERLOCK flagged this message because it contains patterns commonly found in scam SMS targeting Filipino users.",
        action   = "Read the detection details below. When in doubt, do not click links or reply with personal information."
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the best Explanation for the given scam category string.
     * Category may come from Groq AI or the RuleEngine — both are handled.
     */
    fun explain(category: String): Explanation {
        val lower = category.lowercase()
        return explanations
            .firstOrNull { (keyword, _) -> lower.contains(keyword) }
            ?.second
            ?: defaultExplanation
    }

    /**
     * Builds the plain-language explanation card view.
     * Designed to sit between the risk header and the "Detection results" label.
     */
    fun build(context: Context, category: String, riskLevel: String): LinearLayout {
        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val explanation = explain(category)

        // Only show for MEDIUM and HIGH — LOW messages that somehow appear
        // in this panel don't need a scary explanation card.
        val bgColor = when (riskLevel) {
            "HIGH"   -> Color.parseColor("#FFF3E0")  // warm amber tint
            "MEDIUM" -> Color.parseColor("#FFFDE7")  // soft yellow tint
            else     -> Color.parseColor("#F1F8E9")  // light green tint
        }
        val accentColor = when (riskLevel) {
            "HIGH"   -> Color.parseColor("#E65100")
            "MEDIUM" -> Color.parseColor("#F57F17")
            else     -> Color.parseColor("#558B2F")
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(4) }

            // Headline
            addView(TextView(context).apply {
                text     = explanation.headline
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentColor)
            })

            // Detail sentence
            addView(TextView(context).apply {
                text     = explanation.detail
                textSize = 13f
                setTextColor(Color.parseColor("#37474F"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })

            // Divider
            addView(android.view.View(context).apply {
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            })

            // Action line
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(TextView(context).apply {
                    text     = "✅ "
                    textSize = 13f
                })
                addView(TextView(context).apply {
                    text     = explanation.action
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#1B5E20"))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
            })
        }
    }
}