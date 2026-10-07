package com.chipherlock.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner

// ═══════════════════════════════════════════════════════════════════════════
// GroqScamAnalyzer.kt
//
// AI-powered SMS scam analyzer using Groq's FREE inference API.
// Drop-in replacement for GeminiScamAnalyzer.kt — same ScamResult shape,
// same fuseBreakdowns(...) 40% keyword / 60% AI weighting still applies.
//
// WHY GROQ INSTEAD OF GEMINI:
//   • Truly free tier — 14,400 requests/day, NO credit card required
//   • Blazing fast inference (LPU hardware) — results in ~0.5–1s
//   • Llama 3.3 70B has strong multilingual support including Filipino/Tagalog
//   • Simple OpenAI-compatible REST API, easy to maintain
//
// HOW TO GET YOUR FREE API KEY:
//   1. Go to https://console.groq.com
//   2. Sign up with email or Google (no card needed)
//   3. Go to "API Keys" → "Create API Key"
//   4. Paste it in API_KEY below
//
// FREE TIER LIMITS (as of 2026):
//   • 30 requests/minute
//   • 14,400 requests/day
//   • Resets automatically — plenty for real-world SMS scanning
//
// ⚠️  DEMO ONLY — API key is hard-coded in the APK.
//     Before shipping to production, move the key to a secure backend
//     (e.g. Firebase Cloud Function) so it can't be extracted from the APK.
// ═══════════════════════════════════════════════════════════════════════════

object GroqScamAnalyzer {

    // -----------------------------------------------------------------------
    // CONFIGURATION
    // -----------------------------------------------------------------------

    /**
     * API key is injected at build time from local.properties (groq.api.key).
     * Never commit local.properties to version control.
     * See app/build.gradle.kts → buildConfigField for the injection.
     */
    private val API_KEY get() = BuildConfig.GROQ_API_KEY

    /**
     * Model choice:
     *   • "llama-3.3-70b-versatile"  → best quality, strong Tagalog support (default)
     *   • "llama-3.1-8b-instant"     → faster, lower quality — use if hitting rate limits
     *   • "llama4-scout-17b-16e-instruct" → Llama 4, good balance of speed & quality
     *
     * Stick with llama-3.3-70b-versatile unless you scan very high volumes.
     */
    private const val MODEL = "llama-3.3-70b-versatile"

    private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"

    private const val MAX_TOKENS = 256

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(
            CertificatePinner.Builder()
                .add("api.groq.com", "sha256/NSUwR6RBgH3a1fgXJYTEtVVUxeRgIIRwhID90KEv4Qc=")
                .build()
        )
        .build()

    // -----------------------------------------------------------------------
    // RESULT TYPE  (identical to GeminiScamAnalyzer.ScamResult — drop-in safe)
    // -----------------------------------------------------------------------

    data class ScamResult(
        val aiScore: Int,        // 0..100; -1 if the call failed
        val risk: String,        // "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN"
        val reason: String,      // one-sentence explanation OR the error message
        val success: Boolean,    // false if the API call failed for any reason
        val category: String = "⚠️ Suspicious"  // V7: AI-identified attack type
    )

    // -----------------------------------------------------------------------
    // CONFIG CHECK
    // -----------------------------------------------------------------------

    fun isConfigured(): Boolean =
        API_KEY.isNotBlank() && !API_KEY.startsWith("PASTE_")

    // -----------------------------------------------------------------------
    // MAIN ENTRY POINT
    // -----------------------------------------------------------------------

    /**
     * Analyze an SMS body with Groq/Llama. Safe to call from any coroutine;
     * network IO is dispatched to Dispatchers.IO so the UI thread never blocks.
     *
     * Never throws — always returns a ScamResult. Check .success before
     * trusting the score.
     */
    suspend fun analyze(smsText: String, isContact: Boolean = false): ScamResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext ScamResult(
                aiScore = -1,
                risk    = "UNKNOWN",
                reason  = "API key not set — open GroqScamAnalyzer.kt and paste your key",
                success = false
            )
        }

        try {
            // Groq uses the OpenAI chat-completions format.
            // We use a system + user message pair for reliable JSON output.
            val requestJson = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("temperature", 0.2)           // low temp = consistent classification
                put("response_format", JSONObject().apply {
                    put("type", "json_object")    // forces JSON-only output (no markdown fences)
                })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", buildSystemPrompt())
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", buildUserMessage(smsText, isContact))
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                    ?: return@withContext failResult("Empty response from Groq API")

                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(bodyStr)
                            .optJSONObject("error")
                            ?.optString("message")
                    } catch (_: Exception) { null } ?: "HTTP ${response.code}"
                    return@withContext failResult("Groq API error: $errMsg")
                }

                parseResponse(bodyStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            failResult("AI unavailable: ${e.javaClass.simpleName} — ${e.message ?: "no detail"}")
        }
    }

    // -----------------------------------------------------------------------
    // PROMPTS
    // -----------------------------------------------------------------------

    private fun buildSystemPrompt(): String = """
        You are an expert SMS scam analyst for Filipino mobile users. Your job
        is to catch scams that a simple keyword list CANNOT catch. A keyword
        scanner already runs before you. You exist to find the subtle scams
        that have no obvious red flags — no scam keywords, no suspicious URLs,
        no urgency caps — just intent, context, and pattern.

        BE SKEPTICAL OF FRIENDLY MESSAGES. Real scammers in the Philippines
        open with polite Tagalog (po, opo, kuya, ate, ma'am, sir) to build
        trust. A polite tone is NOT proof of legitimacy — it is often the
        opposite. Score on INTENT, not on vocabulary alone.

        ──────────────────────────────────────────────────────────────────
        SUBTLE SCAM PATTERNS YOU MUST CATCH (score 60-100 even if keywords
        and URLs are absent):
        ──────────────────────────────────────────────────────────────────

        1. SOCIAL ENGINEERING / RELATIONSHIP BUILDING
           A stranger making first contact, fishing for personal information.
           Red flags: "Sino po ito?", "Saan ka nagwowork?", "May GCash ka po
           ba?", "Kamusta ka?" from an unknown number. Asks personal questions
           before introducing themselves. Score HIGH (70+) even if polite.

        2. GOVERNMENT / AGENCY IMPERSONATION (NO LINK)
           Claims to be SSS, PhilHealth, BIR, NBI, Pag-IBIG, DSWD, DOLE, or
           a barangay official. Asks you to call a number, text back, or
           reply with a TIN / PhilSys / SSS number / OTP. NO URL needed —
           callback fraud is pure text. Any request to share government IDs
           via SMS = HIGH (80+).

        3. OTP / SIM SWAP FRAUD
           Claims an OTP was "sent by mistake" and asks you to forward it.
           Or asks you to "confirm your number" for verification. Any
           message asking you to FORWARD or SHARE a code, OTP, or
           verification number = HIGH (85+).

        4. JOB OFFER / WORK-FROM-HOME SCAMS
           Unsolicited offers for high-paying remote work, product review
           tasks, "like and earn" schemes, "P2,000/day part-time", crypto
           trading mentorship. Score HIGH (75+) even if no upfront fee is
           mentioned yet — the fee request comes later.

        5. PARCEL / DELIVERY INTERCEPT
           Fake LBC, J&T, Lazada, Shopee, 2GO delivery alerts. Especially
           suspicious: lookalike domains (lbc-ph.online, shopee-ph.site),
           messages claiming "address verification needed", "redelivery fee
           required", "package held at customs". Real couriers do NOT ask
           for fees via SMS link. Score HIGH (75+).

        6. LOAN / INVESTMENT SCAMS
           "Fast approval loan, no collateral", "guaranteed returns", crypto
           investment group invitations, references to Pag-IBIG, Landbank,
           or DBP to appear legitimate. Score HIGH (70+) for any unsolicited
           financial product pitch from an unknown sender.

        7. SEXTORTION / BLACKMAIL
           Claims to have compromising INTIMATE/SEXUAL photos or videos,
           threats to send them to contacts unless paid. Often includes a
           Bitcoin address or GCash number. Score HIGH (90+).
           Category: "💔 Sextortion"

        8. KIDNAPPING / EXTORTION THREATS
           Claims to have kidnapped a family member, threats of physical
           harm, demands for ransom money. "Hostage namin si [name]",
           "Patayin namin siya", "Bigay mo ang pera". Often in Tagalog
           with urgency and large peso amounts. Score HIGH (90+).
           Category: "🔪 Extortion/Kidnap"

        9. ROMANCE SCAM
           Stranger expresses romantic interest unusually fast, may claim
           to be OFW, military, or foreign national. Builds emotional
           connection before requesting money, GCash, or crypto. Even
           without a money request yet, score MEDIUM (50+) for unsolicited
           romantic contact from unknown number. Score HIGH (75+) if money
           or investment is mentioned.
           Category: "💑 Romance Scam"

        10. FAKE EMERGENCY / PERSONAL IMPERSONATION
            Claims to be a family member or friend in crisis: hospital,
            accident, jail, or stranded. "Mama, ako ito, na-ospital ako",
            "Kuya nandito ako sa presinto, paki-bail". Often asks for
            GCash or load immediately. Score HIGH (80+).
            Category: "🆘 Fake Emergency" or "👤 Personal Impersonation"

        11. CRYPTO / NFT / PIG BUTCHERING
            Unsolicited mention of crypto trading groups, guaranteed profits,
            "mentors", NFT projects, or investment platforms with referral
            codes. Pig butchering: builds trust first then introduces
            "investment opportunity". Score HIGH (75+) even without an
            explicit money request — the pitch itself is the scam.
            Category: "💱 Crypto/NFT Scam"

        12. MARKETPLACE / ADVANCE FEE FRAUD
            Fake buyer overpays and asks for refund, fake seller asks for
            advance payment before delivery, fake escrow services for
            Carousell/Facebook Marketplace deals. Score HIGH (75+) for
            any "overpayment refund" or "advance fee before delivery" pattern.
            Category: "🛒 Marketplace Scam"

        13. UTILITY / SERVICE DISCONNECTION
            Claims to be Meralco, Maynilad, PLDT, Globe, or DITO with
            a disconnection threat unless payment is made immediately via
            GCash or link. Real utilities never demand instant SMS payment.
            Score HIGH (80+). Category: "💡 Utility Threat"

        14. SIM SWAP / ACCOUNT TAKEOVER
            Asks victim to confirm personal details "for SIM registration",
            requests forwarding of an OTP "by mistake", or impersonates
            Globe/Smart to "upgrade" the SIM. Score HIGH (85+).
            Category: "📲 SIM Swap" or "🧑‍💻 Account Takeover"

        15. DATA HARVESTING
            Disguised as a survey, raffle entry, loyalty program, or
            government registration requiring full name, birthdate, address,
            mother's maiden name, or government ID numbers. No obvious
            scam keywords — just systematic personal data collection.
            Score MEDIUM (45+) for any unsolicited data collection form.
            Category: "📋 Data Harvesting"

        ──────────────────────────────────────────────────────────────────
        OBVIOUS SCAM INDICATORS (the keyword list also catches these — you
        should agree by also scoring HIGH):
        ──────────────────────────────────────────────────────────────────
        • Any URL or link, especially:
            - Hyphenated brand impersonations: bdo-secure.com, dswd-ayuda.online
            - Bracket-obfuscated dots: domain[.]com, site[dot]ph
            - Non-official TLDs (.online, .xyz, .info, .click, .site)
            - Shorteners: bit.ly, tinyurl, rb.gy, t.co
        • Prize / ayuda / voucher / reward claims via SMS
        • Bank or e-wallet impersonation (BDO, BPI, Metrobank, GCash, Maya)
        • Urgency disguised as helpfulness ("bago maubos", "limited slots")
        • Sender is a personal mobile number claiming to be official

        ──────────────────────────────────────────────────────────────────
        LEGITIMATE messages (score 0-30) — DO NOT FLAG THESE:
        ──────────────────────────────────────────────────────────────────

        VERIFIED-SENDER LEGITIMATE PATTERNS (score 0-20 even if scam keywords
        like "account", "verify", or "bank" appear in the body):

        • BANK TRANSACTION ALERT — Sender like BPI, BDO, METROBANK, etc.
          Body contains: amount (PHP X,XXX.XX) + masked account ending in
          last 4 digits + timestamp + optional location + balance + optional
          callback to a LANDLINE (e.g. "Call 889-10000"). NO clickable URL.
          NO instruction to click, claim, or verify online.
          → ALWAYS score 0-20 LOW. This is a routine notification.

        • E-WALLET TRANSACTION — Sender like GCASH, MAYA, PAYMAYA, GRABPAY.
          Body contains: "You received/sent PHP X,XXX.XX" + masked phone
          (09**X****XX format) + reference number + new available balance.
          NO clickable URL. NO instruction to verify or click anywhere.
          → ALWAYS score 0-20 LOW.

        • TELCO PROMO / BALANCE — Sender like GLOBE, SMART, TM, TNT, DITO.
          Body references USSD codes (*143#, *214#) or text short-codes
          (text BAL to 222). NO clickable URL. NO money request.
          → ALWAYS score 0-20 LOW.

        • COURIER / DELIVERY STATUS — Sender like LBC, JNT, 2GO, NINJAVAN.
          Body contains tracking number + status + branch name. NO link.
          NO request for "redelivery fee" or "address verification fee."
          → ALWAYS score 0-20 LOW.

        OTHER LEGITIMATE PATTERNS:

        • Plain OTP codes with NO links and NO "forward this" instruction
        • Personal messages between known parties with normal content,
          including informal Filipino / jejemon style between family
          members (mom/dad/anak/kuya/ate/tito/tita) discussing daily
          things like food, location, or school — these are not scams
        • Promo / marketing from a known brand short-code with no money
          requests and no suspicious links

       CRITICAL DISTINCTION: A REAL bank/wallet/telco notification NEVER
        contains a clickable URL. A SPOOFED one always does. Use the
        presence of a URL as the deciding factor when the sender ID looks
        legitimate:
          • Sender = BPI/GCASH/etc + NO URL + transactional format → LOW
          • Sender = BPI/GCASH/etc + URL present → HIGH (impersonation)

        CALLBACK NUMBERS ARE LEGITIMACY SIGNALS, NOT SCAM SIGNALS.
        When a verified-sender message says "Call 889-10000" or "Call
        02-8631-8000" or similar landline / hotline numbers, this is the
        bank/telco/courier giving the customer a way to verify the
        notification. Real institutions provide callback hotlines because
        they have call centers. Scammers do NOT — scammers direct victims
        to URLs because URLs can capture credentials.

        Therefore:
          • "Not you? Call 889-10000" in a BPI message     → LEGITIMATE
          • "Reply STOP to 8080" in a Globe promo          → LEGITIMATE
          • "Click here to verify: bdo-secure.online"       → SCAM
          • "Verify at gcash-secure[.]online or be locked"  → SCAM

        DO NOT count callback numbers as scam indicators.
        DO NOT count ALL-CAPS, capitalized amounts (PHP 1,250.00), or
        formatted timestamps (May 16, 2026 11:42 AM) as scam indicators —
        these are normal transactional formatting that legit institutions
        use precisely because the data is structured.

        ──────────────────────────────────────────────────────────────────
        SCORING RULES:
        ──────────────────────────────────────────────────────────────────
        • 0-39   → LOW    (genuinely benign)
        • 40-69  → MEDIUM (suspicious context, missing one clear indicator)
        • 70-100 → HIGH   (clear scam intent — pattern, context, or content)

        DEFAULT POSTURE: When in doubt, lean HIGHER. Missing a real scam
        harms the user; over-flagging a legitimate message only annoys them.

        ──────────────────────────────────────────────────────────────────
        KNOWN CONTACT RULE (overrides all other rules):
        ──────────────────────────────────────────────────────────────────
        If the context says "Sender is a KNOWN CONTACT", apply strict
        leniency. Everyday messages from contacts — "hi", "kumusta",
        "hi nak", "kumain ka na?", "saan ka?", family check-ins, casual
        conversation — ALWAYS score 0-15 (LOW). Category = "New Message".

        Only flag a contact message HIGH if it contains ALL of:
          1. A suspicious URL or link
          2. A financial demand or OTP request
        A contact sending "hi nak" is never social engineering — it is a
        family member. Never flag informal greetings from known contacts.

        OUTPUT FORMAT (mandatory — JSON only, no markdown, no preamble):
        {
          "score":    <integer 0-100>,
          "risk":     "<LOW|MEDIUM|HIGH>",
          "reason":   "<one short English sentence naming the specific scam pattern>",
          "category": "<exactly one of the labels below>"
        }

        CATEGORY LABELS (pick the single best match):

        FINANCIAL & ACCOUNT:
          "💳 Financial Fraud"         — bank/e-wallet impersonation, GCash/Maya account scams
          "💰 Loan/Investment Scam"    — fast loans, guaranteed returns, Ponzi/pyramid, paluwagan fraud
          "💱 Crypto/NFT Scam"         — fake crypto trading, NFT investment, pig butchering schemes
          "🔑 Credential Theft"        — OTP harvesting, password/PIN requests, login hijack
          "📲 SIM Swap"                — convincing victim to transfer/give up their SIM or number
          "🧑‍💻 Account Takeover"        — impersonating a service to hijack social media, email, GCash

        LINKS & TECH:
          "🔗 Phishing Link"           — lookalike domains, suspicious URLs, link-based credential traps
          "🦠 Malware Link"            — links that download spyware, stalkerware, or RATs to the device
          "🖥️ Tech Support Scam"       — fake IT/device support, remote access requests

        IDENTITY & SOCIAL:
          "🤖 Social Engineering"      — relationship building, fishing for personal info from unknown sender
          "💑 Romance Scam"            — fake romantic interest leading to money requests or investment pitch
          "👤 Personal Impersonation"  — pretending to be a friend, family member, or classmate in need
          "🆘 Fake Emergency"          — false claims that a family member is hospitalized, arrested, or in danger

        PRIZES & REWARDS:
          "🎰 Prize/Lottery Scam"      — fake lottery wins, raffle prizes, voucher claims, ayuda release

        GOVERNMENT & AUTHORITY:
          "🏛️ Gov't Impersonation"     — fake SSS, PhilHealth, DSWD, NBI, BIR, DOLE, barangay officials
          "⚖️ Legal Threat"            — fake arrest warrants, court summons, NBI entrapment threats
          "💡 Utility Threat"          — fake Meralco, Maynilad, PLDT, Globe disconnection notices

        JOB & COMMERCE:
          "💼 Job/Task Scam"           — fake job offers, work-from-home, like-and-earn, product review tasks
          "🛒 Marketplace Scam"        — fake Lazada/Shopee sellers, bogus buy/sell, advance fee fraud
          "📦 Delivery/Parcel Scam"    — fake LBC/J&T/Ninja Van interception, redelivery fee demands

        HEALTH & CHARITY:
          "🏥 Medical/Health Scam"     — fake medicines, miracle supplements, fake health authority
          "🤝 Charity/Relief Scam"     — fake typhoon/calamity relief, donation solicitation fraud

        THREATS:
          "💔 Sextortion"              — blackmail specifically using intimate/sexual content or images
          "🔪 Extortion/Kidnap"        — ransom demands, kidnapping threats, harm threats, hostage scams

        OTHER:
          "📋 Data Harvesting"         — disguised surveys or forms collecting personal/financial info
          "New Message"                — not a scam, benign message
    """.trimIndent()

    private fun buildUserMessage(smsText: String, isContact: Boolean): String {
        val safeBody = smsText
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val context = if (isContact)
            "Sender is a KNOWN CONTACT in the user's address book."
        else
            "Sender is UNKNOWN (not in contacts)."
        return "Analyze this SMS message:\n\"$safeBody\"\n\nContext: $context"
    }

    // -----------------------------------------------------------------------
    // RESPONSE PARSING  (OpenAI-compatible format)
    // -----------------------------------------------------------------------

    private fun parseResponse(bodyStr: String): ScamResult {
        return try {
            val outer = JSONObject(bodyStr)

            // Groq wraps the reply in choices[0].message.content
            val choices = outer.optJSONArray("choices")
                ?: return failResult("No choices in Groq response")
            if (choices.length() == 0) return failResult("Empty choices array")

            val message = choices.getJSONObject(0)
                .optJSONObject("message")
                ?: return failResult("No message in choice")

            // Check finish_reason — "stop" is normal; "length" means truncated
            val finishReason = choices.getJSONObject(0).optString("finish_reason", "")
            if (finishReason == "length") {
                return failResult("AI response was cut off (max tokens hit)")
            }

            val rawText = message.optString("content", "").trim()
            if (rawText.isEmpty()) return failResult("Empty content in Groq response")

            // response_format=json_object should give clean JSON, but be defensive
            val firstBrace = rawText.indexOf('{')
            val lastBrace  = rawText.lastIndexOf('}')
            if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
                return failResult("AI response was not JSON: ${rawText.take(80)}")
            }
            val pure = rawText.substring(firstBrace, lastBrace + 1)

            val parsed = JSONObject(pure)
            ScamResult(
                aiScore  = parsed.getInt("score").coerceIn(0, 100),
                risk     = parsed.optString("risk",     "UNKNOWN").uppercase(),
                reason   = parsed.optString("reason",   "No explanation provided"),
                category = parsed.optString("category", "⚠️ Suspicious"),
                success  = true
            )
        } catch (e: Exception) {
            e.printStackTrace()
            failResult("Could not parse Groq response: ${e.message}")
        }
    }

    private fun failResult(reason: String) = ScamResult(
        aiScore = -1,
        risk    = "UNKNOWN",
        reason  = reason,
        success = false
    )
}