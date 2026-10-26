package com.chipherlock.app

// ---------------------------------------------------------------------------
// SmsReceiver.kt  (Session 9 — Blueprint 1: Score Transparency)
//
// Changes from Session 7:
//   • analyzeMessage() now returns ScoreBreakdown instead of AnalysisResult
//   • Every triggered keyword/rule is recorded as a ScoreFactor
//   • fireNotification() appends a compact "Score breakdown:" line
//   • saveAlertToHistory() updated to pass factors into ScamHistoryEntry
// ---------------------------------------------------------------------------

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SmsReceiver : BroadcastReceiver() {

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        // We listen to two actions:
        //   • SMS_DELIVER  — fires ONLY to the default SMS app (and is the one
        //     that grants permission to write to the inbox provider)
        //   • SMS_RECEIVED — fires to ALL apps for monitoring
        //
        // To avoid processing the same SMS twice (and firing duplicate
        // notifications), pick exactly one based on whether we're default:
        val isDefault = try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (e: Exception) { false }

        val shouldHandle = when {
            isDefault  && action == Telephony.Sms.Intents.SMS_DELIVER_ACTION   -> true
            !isDefault && action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION  -> true
            else -> false
        }
        if (!shouldHandle) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "Unknown"
        val body   = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = try { messages[0].timestampMillis } catch (e: Exception) {
            System.currentTimeMillis()
        }

        // If we're the default SMS app, *we* are responsible for writing the
        // incoming SMS to the inbox content provider. Without this, the SMS
        // is only ever in memory as a broadcast and never appears in Inbox.
        if (isDefault && action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            writeIncomingSmsToInbox(context, sender, body, timestamp)
        }

        // --- V7: Access List check (replaces flat blacklist) ---
        // Reads access_list_prefs (V7). Falls back to blacklist_prefs if not
        // yet migrated. ALLOW → skip analysis, fire refresh. BLOCK → drop.
        try {
            val accessPrefs = context.getSharedPreferences("access_list_prefs", Context.MODE_PRIVATE)
            val accessJson  = accessPrefs.getString("access_list_json", null)
                ?: context.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
                    .getString("blacklist_json", "[]")
                ?: "[]"
            val accessArray = JSONArray(accessJson)
            for (i in 0 until accessArray.length()) {
                val entry       = accessArray.optJSONObject(i) ?: continue
                val entrySender = entry.optString("sender", entry.optString("number", ""))
                if (entrySender.isEmpty()) continue
                if (!entrySender.trim().equals(sender.trim(), ignoreCase = true)) continue
                when (entry.optString("type", "BLOCK")) {
                    "ALLOW" -> {
                        NotificationCounter.increment(context, SmsCategory.INBOX)
                        val refreshIntent = Intent("com.chipherlock.SMS_RECEIVED_REFRESH").apply {
                            putExtra("sender", sender)
                        }
                        androidx.localbroadcastmanager.content.LocalBroadcastManager
                            .getInstance(context).sendBroadcast(refreshIntent)
                        return
                    }
                    else -> return  // BLOCK — drop silently
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --- Keyword analysis (always synchronous, cheap) ---
        val keywordBreakdown = analyzeMessage(sender, body, context)

        val settingsPrefs = context.getSharedPreferences("chipherlock_settings", Context.MODE_PRIVATE)
        val autoQuarantine = settingsPrefs.getBoolean("auto_quarantine_enabled", false)

        // --- Decide whether to invoke Groq ---
        // AI runs on EVERY incoming SMS that isn't blacklisted. The keyword
        // list cannot catch socially-engineered scams that contain no URL
        // and no flagged words (e.g. "Hi po, may extra job ako para sa'yo,
        // text mo lang ako" — pure social engineering, scores 0 on keywords).
        // Gating AI behind a keyword threshold meant those slipped through
        // silently. The AI is the safety net, not the second opinion.
        //
        // Cost: one Groq call per non-blacklisted incoming SMS. Free tier
        // is 14,400/day so this is comfortable for normal use.
        val needsAiVerification = GroqScamAnalyzer.isConfigured()

        if (!needsAiVerification) {
            // Fast synchronous path — original keyword-only behavior preserved.
            try {
                finalizeMessage(context.applicationContext, sender, body, keywordBreakdown, autoQuarantine)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        // --- AI verification path ---
        // goAsync() keeps the broadcast alive for the network call. We bound
        // the wait at 8 seconds so we always release the broadcast in time —
        // if Groq stalls, we fall back to the keyword breakdown.
        //
        // IMPORTANT: capture applicationContext, NOT the receiver context.
        // The receiver context is only valid for the duration of onReceive();
        // once we return from this method, accessing it can throw or no-op.
        // applicationContext lives for the lifetime of the process.
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val finalBd: ScoreBreakdown = try {
                val isContact = ContactsHelper.isContact(appCtx, sender)
                val ai = withTimeoutOrNull(8_000L) {
                    GroqScamAnalyzer.analyze(body, isContact = isContact)
                }
                if (ai != null) {
                    SmsFragment.fuseBreakdowns(keywordBreakdown, ai, sender)
                } else {
                    keywordBreakdown
                }
            } catch (e: Exception) {
                e.printStackTrace()
                keywordBreakdown
            }

            // finalizeMessage does notification + SharedPreferences writes +
            // broadcast — all of which are safe on a background thread.
            // Wrap in its own try so a failure here can't leak the goAsync
            // pending result and ANR the receiver.
            try {
                finalizeMessage(appCtx, sender, body, finalBd, autoQuarantine)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pending.finish()
            }
        }
    }

    // ── Post-analysis pipeline ──────────────────────────────────────────
    // Notification + history + quarantine + refresh broadcast. Same steps
    // regardless of whether the breakdown came from keyword-only or fused
    // (keyword + Groq), so both code paths converge here.
    private fun finalizeMessage(
        context: Context,
        sender: String,
        body: String,
        breakdown: ScoreBreakdown,
        autoQuarantine: Boolean
    ) {
        // Record this message's risk in the per-message index so the
        // conversation viewer can filter spoofed-sender threads correctly.
        // We look up smsId by querying the SMS provider for the just-written
        // row — incoming messages don't carry their final smsId at receive
        // time; the OS assigns it when the row lands in the inbox.
        try {
            context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "address = ? AND body = ?",
                arrayOf(sender, body),
                "date DESC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val smsId = c.getLong(0)
                    SmsFragment.setMessageRisk(context, smsId, breakdown.level)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        fireNotification(context, sender, body, breakdown)

        // --- History log (MEDIUM / HIGH only, non-contacts only) ---
        // Messages from known contacts are never saved to scam history
        // even if scored MEDIUM/HIGH — they belong in Inbox, not Scams.
        val senderIsContact = ContactsHelper.isContact(context, sender)

        // --- Body deduplication: catch multi-number coordinated attacks ---
        // If the exact same message body is already flagged as MEDIUM/HIGH
        // from a different sender, upgrade this one to MEDIUM even if the
        // AI returned LOW. Social engineering scams are often blasted from
        // multiple numbers simultaneously — one gets caught, the rest slip
        // through on the next AI call unless we check the body.
        val bodyKey = body.take(60)
        val bodyAlreadyFlagged: Boolean = try {
            val qPrefs = context.getSharedPreferences("quarantine_prefs", Context.MODE_PRIVATE)
            val qArray = org.json.JSONArray(qPrefs.getString("quarantine_json", "[]") ?: "[]")
            val inQuarantine = (0 until qArray.length()).any { i ->
                val e = qArray.optJSONObject(i) ?: return@any false
                e.optString("messageBody", "").take(60) == bodyKey &&
                        e.optString("sender", "") != sender
            }
            if (inQuarantine) true else {
                val hPrefs = context.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                val hArray = org.json.JSONArray(hPrefs.getString("scam_history_json", "[]") ?: "[]")
                (0 until hArray.length()).any { i ->
                    val e = hArray.optJSONObject(i) ?: return@any false
                    val lvl = e.optString("riskLevel", "LOW")
                    e.optString("body", "").take(60) == bodyKey &&
                            e.optString("sender", "") != sender &&
                            (lvl == "MEDIUM" || lvl == "HIGH")
                }
            }
        } catch (_: Exception) { false }

        // Upgrade LOW to MEDIUM if body is a known flagged pattern
        val effectiveBreakdown = if (breakdown.level == "LOW" && bodyAlreadyFlagged) {
            breakdown.copy(
                level    = "MEDIUM",
                total    = 48,
                category = "Social Engineering",
                factors  = breakdown.factors + ScoreFactor(
                    "Same message body already flagged from another sender", 48
                )
            )
        } else breakdown

        if (effectiveBreakdown.level != "LOW" && !senderIsContact) {
            saveAlertToHistory(context, sender, body, effectiveBreakdown)
        }

        // --- V7: Increment per-category notification counter ---
        // Contacts always route to INBOX regardless of risk score.
        if (senderIsContact) {
            NotificationCounter.increment(context, SmsCategory.INBOX)
        } else {
            when (effectiveBreakdown.level) {
                "HIGH"   -> {
                    if (autoQuarantine) NotificationCounter.increment(context, SmsCategory.QZONE)
                    else NotificationCounter.increment(context, SmsCategory.SCAMS)
                }
                "MEDIUM" -> NotificationCounter.increment(context, SmsCategory.SCAMS)
                else     -> NotificationCounter.increment(context, SmsCategory.INBOX)
            }
        }

        // --- Auto-Quarantine ---
        if (autoQuarantine && effectiveBreakdown.level != "LOW") {
            quarantineMessage(context, sender, body, effectiveBreakdown)
        }

        // --- Broadcast for real-time inbox refresh ---
        val refreshIntent = Intent("com.chipherlock.SMS_RECEIVED_REFRESH").apply {
            putExtra("sender", sender)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(context).sendBroadcast(refreshIntent)
    }

    // Writes the incoming SMS to Android's inbox provider so the user's Inbox
    // tab actually shows it. Only callable when this app is the default SMS app.
    private fun writeIncomingSmsToInbox(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long
    ) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY,    body)
                put(Telephony.Sms.DATE,    timestamp)
                put(Telephony.Sms.READ,    0)
                put(Telephony.Sms.SEEN,    0)
                put(Telephony.Sms.TYPE,    Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -----------------------------------------------------------------------
    // Core analysis — returns a full ScoreBreakdown
    // -----------------------------------------------------------------------

    fun analyzeMessage(sender: String, body: String, ctx: Context? = null): ScoreBreakdown {
        val lower   = body.lowercase()
        var score   = 0
        val factors = mutableListOf<ScoreFactor>()
        var category = "New Message"

        // --- Filipino keyword map ---
        val filipinoKeywords = mapOf(
            "gcash"         to 20,
            "maya"          to 15,
            "nanalo"        to 25,
            "premyo"        to 25,
            "warrant"       to 30,
            "arestuhin"     to 30,
            "hulihin"       to 30,
            "bayad"         to 15,
            "padala"        to 15,
            "pera"          to 15,
            "bsp"           to 15,
            "philhealth"    to 15,
            "sss"           to 15,
            "pagibig"       to 15,
            "bir"           to 15,
            "pulis"         to 20,
            "nbi"           to 20,
            "kaso"          to 20,
            "imbestigasyon" to 25,
            "korte"         to 25,
            "mandato"       to 25,
            "suspendido"    to 20,
            "i-verify"      to 20,
            "mag-click"     to 15,
            "mag-login"     to 20
        )

        // --- English / generic keyword map ---
        val englishKeywords = mapOf(
            "winner"          to 25,
            "otp"             to 20,
            "prize"           to 20,
            "suspended"       to 20,
            "blocked"         to 20,
            "password"        to 20,
            "congratulations" to 20,
            "bit.ly"          to 25,
            "tinyurl"         to 25,
            ".tk"             to 20,
            "verify"          to 15,
            "urgent"          to 15,
            "claim"           to 15,
            "bank"            to 10,
            "account"         to 10,
            "credit card"     to 15,
            "atm"             to 10,
            "pin"             to 10,
            "ssn"             to 20,
            "social security" to 20,
            "lottery"         to 25,
            "free gift"       to 20,
            "you have won"    to 30,
            "click here"      to 20,
            "limited time"    to 15,
            "act now"         to 15,
            "expires"         to 10,
            "confirm"         to 10,
            "login"           to 15,
            "credential"      to 20,
            "arrest"          to 25,
            "legal action"    to 25,
            "government"      to 10,
            "official"        to 10,
            "bdo"             to 10,
            "metrobank"       to 10,
            "bpi"             to 10,
            "unionbank"       to 10,
            "security alert"  to 20,
            "unauthorized"    to 20,
            "restore"         to 15,
            "identity"        to 15,
            "update"          to 10,
            "access"          to 10,
            // Government aid / voucher scams
            "ayuda"           to 25,
            "voucher"         to 20,
            "grocery voucher" to 30,
            "dswd"            to 25,
            "kunin na"        to 20,
            "bago maubos"     to 25,
            "i-click"         to 20,
            "naka-lista"      to 20,
            // Suspicious TLDs for lookalike domains
            ".online"         to 30,
            ".xyz"            to 25,
            ".site"           to 25,
            ".click"          to 30,
            ".live"           to 20,
            ".info"           to 15,
            // Hyphenated brand impersonation
            "bdo-"            to 25,
            "bpi-"            to 25,
            "gcash-"          to 25,
            "dswd-"           to 30,
            "maya-"           to 25,
            "sss-"            to 25,
            "philhealth-"     to 25
        )

        // Category-to-keyword association for first-match detection
        // (category set once — first cluster that fires wins)
        val categoryHints = mapOf(
            "gcash"           to "Financial Fraud",
            "maya"            to "Financial Fraud",
            "bayad"           to "Financial Fraud",
            "padala"          to "Financial Fraud",
            "pera"            to "Financial Fraud",
            "bank"            to "Financial Fraud",
            "bdo"             to "Financial Fraud",
            "metrobank"       to "Financial Fraud",
            "bpi"             to "Financial Fraud",
            "unionbank"       to "Financial Fraud",
            "account"         to "Financial Fraud",
            "credit card"     to "Financial Fraud",
            "atm"             to "Financial Fraud",
            "nanalo"          to "Prize Scam",
            "premyo"          to "Prize Scam",
            "winner"          to "Prize Scam",
            "prize"           to "Prize Scam",
            "congratulations" to "Prize Scam",
            "lottery"         to "Prize Scam",
            "free gift"       to "Prize Scam",
            "you have won"    to "Prize Scam",
            "warrant"         to "Legal Threat",
            "arestuhin"       to "Legal Threat",
            "hulihin"         to "Legal Threat",
            "kaso"            to "Legal Threat",
            "imbestigasyon"   to "Legal Threat",
            "korte"           to "Legal Threat",
            "mandato"         to "Legal Threat",
            "arrest"          to "Legal Threat",
            "legal action"    to "Legal Threat",
            "bsp"             to "Gov't Impersonation",
            "philhealth"      to "Gov't Impersonation",
            "sss"             to "Gov't Impersonation",
            "pagibig"         to "Gov't Impersonation",
            "bir"             to "Gov't Impersonation",
            "pulis"           to "Gov't Impersonation",
            "nbi"             to "Gov't Impersonation",
            "government"      to "Gov't Impersonation",
            "official"        to "Gov't Impersonation",
            "ssn"             to "Gov't Impersonation",
            "social security" to "Gov't Impersonation",
            "bit.ly"          to "Phishing Link",
            "tinyurl"         to "Phishing Link",
            ".tk"             to "Phishing Link",
            "click here"      to "Phishing Link",
            "mag-click"       to "Phishing Link",
            "otp"             to "Credential Theft",
            "password"        to "Credential Theft",
            "verify"          to "Credential Theft",
            "i-verify"        to "Credential Theft",
            "login"           to "Credential Theft",
            "mag-login"       to "Credential Theft",
            "credential"      to "Credential Theft",
            "pin"             to "Credential Theft",
            "suspended"       to "Credential Theft",
            "suspendido"      to "Credential Theft",
            "blocked"         to "Credential Theft",
            "restore"         to "Credential Theft",
            "identity"        to "Credential Theft",
            "security alert"  to "Credential Theft",
            "unauthorized"    to "Credential Theft",
            "update"          to "Credential Theft",
            "access"          to "Credential Theft"
        )

        fun recordHit(keyword: String, points: Int) {
            score += points
            factors.add(ScoreFactor("\"$keyword\" detected", points))
            if (category == "New Message") {
                category = categoryHints[keyword] ?: "New Message"
            }
        }

        // Scan Filipino keywords
        for ((kw, pts) in filipinoKeywords) {
            if (lower.contains(kw)) recordHit(kw, pts)
        }

        // Scan English keywords
        for ((kw, pts) in englishKeywords) {
            if (lower.contains(kw)) recordHit(kw, pts)
        }

        // --- Structural signals ---
        val hasUrl = lower.contains("http://") || lower.contains("https://")
        if (hasUrl) {
            score += 15
            factors.add(ScoreFactor("Suspicious URL detected", 15))
            if (category == "New Message") category = "Phishing Link"
        }

        // Bracket-obfuscated URLs: domain[.]com or domain[dot]com
        if (lower.contains("[.]") || lower.contains("[dot]")) {
            score += 40
            factors.add(ScoreFactor("Obfuscated phishing link [.] detected", 40))
            if (category == "New Message") category = "Phishing Link"
        }

        val isShortCode = sender.matches(Regex("\\d{4,6}"))
        if (isShortCode) {
            score += 10
            factors.add(ScoreFactor("Short-code sender (${sender})", 10))
        }

        // Excessive uppercase is a scam indicator for UNKNOWN senders only.
        // Real bank/wallet/telco alerts naturally contain many caps because
        // they use formatted amounts (PHP 1,250.00), brand names (BPI, GCASH),
        // and capitalized locations (SM CITY CEBU). Skip this rule when the
        // sender ID is on the verified list.
        if (!VerifiedSenders.isVerified(sender)) {
            val uppercaseCount = body.count { it.isUpperCase() }
            if (uppercaseCount > 20) {
                score += 10
                factors.add(ScoreFactor("Excessive uppercase letters ($uppercaseCount)", 10))
            }
        }

        // --- Phase 3.3: Country code validation ---
        // Applies a +15 trust penalty for messages from non-Philippine numbers.
        // Skipped for alphanumeric senders (registered short codes) and when
        // travelling mode is ON. Context is optional — penalty skipped if null.
        if (ctx != null) {
            val countryResult = PhoneCountryValidator.validate(sender, ctx)
            if (countryResult.shouldPenalise) {
                score += countryResult.penalty
                factors.add(ScoreFactor(countryResult.factorLabel, countryResult.penalty))
            }
        }

        // --- Risk level ---
        // Cap raw score at 100 — stacking many keywords can otherwise push it over.
        val cappedScore = score.coerceIn(0, 100)
        val level = when {
            cappedScore >= 60 -> "HIGH"
            cappedScore >= 30 -> "MEDIUM"
            else              -> "LOW"
        }

        return ScoreBreakdown(
            factors  = factors,
            total    = cappedScore,
            level    = level,
            category = category
        )
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private fun fireNotification(
        context: Context,
        sender: String,
        body: String,
        breakdown: ScoreBreakdown
    ) {
        val channelId = "scam_alerts"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Scam Alerts", NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (breakdown.level != "LOW") {
                putExtra("navigate_to_history", true)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Store pending alert for History tab navigation
        if (breakdown.level != "LOW") {
            context.getSharedPreferences("scam_alert_prefs", Context.MODE_PRIVATE)
                .edit().putString("pending_alert", sender).apply()
        }

        val (title, shortText) = when (breakdown.level) {
            "HIGH"   -> "HIGH RISK SMS from $sender" to
                    "Risk Score: ${breakdown.total}/100 — ${breakdown.category}"
            "MEDIUM" -> "MEDIUM RISK SMS from $sender" to
                    "Risk Score: ${breakdown.total}/100 — ${breakdown.category}"
            else     -> "New message from $sender" to body.take(60)
        }

        // Compact breakdown line — top 4 factors by points
        val compactBreakdown: String = if (breakdown.factors.isNotEmpty()) {
            val top = breakdown.factors
                .sortedByDescending { it.points }
                .take(4)
                .joinToString(" • ") { f ->
                    "\"${f.label.removePrefix("\"").substringBefore("\"")
                        .ifBlank { f.label }}\" +${f.points}"
                }
            "\nScore breakdown: $top = ${breakdown.total}/100"
        } else ""

        val bigText = when (breakdown.level) {
            "HIGH", "MEDIUM" ->
                "From: $sender\n${body.take(120)}\n\n" +
                        "${breakdown.category} detected — Risk Score: ${breakdown.total}/100" +
                        compactBreakdown
            else ->
                body
        }

        // ── Visual styling by risk level ────────────────────────────────
        // Without explicit color the system uses its default tint (blue on
        // most Pixels), making HIGH and MEDIUM look identical at a glance.
        // We tint the accent strip + small icon so the user can tell the
        // severity from the notification shade without opening the app.
        val accentColor: Int = when (breakdown.level) {
            "HIGH"   -> 0xFFD32F2F.toInt()   // red
            "MEDIUM" -> 0xFFE65100.toInt()   // amber/orange
            else     -> 0xFF2E7D32.toInt()   // green (LOW / informational)
        }

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(accentColor)
            .setColorized(true)              // applies accent to header area on Android O+
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(
                if (breakdown.level == "HIGH") NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    // -----------------------------------------------------------------------
    // History persistence
    // -----------------------------------------------------------------------

    private fun saveAlertToHistory(
        context: Context,
        sender: String,
        body: String,
        breakdown: ScoreBreakdown
    ) {
        val prefs     = context.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
        val existing  = JSONArray(prefs.getString("scam_history_json", "[]") ?: "[]")

        val factorsArray = JSONArray()
        for (f in breakdown.factors) {
            factorsArray.put(JSONObject().apply {
                put("label",  f.label)
                put("points", f.points)
            })
        }

        val entry = JSONObject().apply {
            put("sender",    sender)
            put("body",      body)
            put("riskScore", breakdown.total)
            put("riskLevel", breakdown.level)
            put("category",  breakdown.category)
            put("factors",   factorsArray)
            put("timestamp", System.currentTimeMillis())
        }

        val updated = JSONArray()
        updated.put(entry)
        for (i in 0 until minOf(existing.length(), 99)) updated.put(existing.getJSONObject(i))

        prefs.edit().putString("scam_history_json", updated.toString()).apply()
    }

    // -----------------------------------------------------------------------
    // Quarantine
    // -----------------------------------------------------------------------

    private fun quarantineMessage(
        context: Context,
        sender: String,
        body: String,
        breakdown: ScoreBreakdown
    ) {
        // Look up the actual smsId from the SMS provider so DELETE ALL can
        // physically remove this row from the phone. Without this, the entry
        // gets saved with smsId=-1 and deleteFromPhone() is a no-op, causing
        // the scam SMS to reappear every time Q-Zone is cleared.
        val actualSmsId: Long = try {
            context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "address = ? AND body = ?",
                arrayOf(sender, body),
                "date DESC"
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) { -1L }

        val prefs    = context.getSharedPreferences("quarantine_prefs", Context.MODE_PRIVATE)
        val existing = JSONArray(prefs.getString("quarantine_json", "[]") ?: "[]")

        // Field names MUST match QuarantineEntry.fromJson exactly:
        //   "messageBody" (not "body"), "smsId" as a Long (not the string "-1"),
        //   and the new "factors" array so Q-Zone can show the fusion breakdown.
        val factorsArray = JSONArray()
        breakdown.factors.forEach { f ->
            factorsArray.put(JSONObject().apply {
                put("label",  f.label)
                put("points", f.points)
            })
        }

        val entry = JSONObject().apply {
            put("sender",      sender)
            put("messageBody", body)
            put("riskScore",   breakdown.total)
            put("riskLevel",   breakdown.level)
            put("category",    breakdown.category)
            put("timestamp",   System.currentTimeMillis())
            put("smsId",       actualSmsId)
            put("factors",     factorsArray)
        }

        val updated = JSONArray()
        updated.put(entry)
        for (i in 0 until minOf(existing.length(), 99)) updated.put(existing.getJSONObject(i))

        prefs.edit().putString("quarantine_json", updated.toString()).apply()

        // Auto-blacklist — SKIP if sender ID is a verified service.
        // A scam from "BPI" is a spoof of that ID — the real bank also
        // sends from "BPI". Blacklisting "BPI" would silently block the
        // user's real BPI alerts forever. The scam still gets quarantined
        // above; only the blacklist write is gated.
        if (!VerifiedSenders.isVerified(sender)) {
            try {
                // V7: auto-block writes to access_list_prefs with type="BLOCK"
                val accessPrefs   = context.getSharedPreferences("access_list_prefs", Context.MODE_PRIVATE)
                val accessList    = JSONArray(accessPrefs.getString("access_list_json", "[]") ?: "[]")
                val alreadyListed = (0 until accessList.length()).any {
                    val e = accessList.optJSONObject(it) ?: return@any false
                    val s = e.optString("sender", e.optString("number", ""))
                    s.trim().equals(sender.trim(), ignoreCase = true)
                }
                if (!alreadyListed) {
                    val newEntry = JSONObject().apply {
                        put("sender",    sender)
                        put("type",      "BLOCK")
                        put("reason",    "Auto-Scam")
                        put("timestamp", System.currentTimeMillis())
                    }
                    val newList = JSONArray()
                    newList.put(newEntry)
                    for (i in 0 until accessList.length()) {
                        accessList.optJSONObject(i)?.let { newList.put(it) }
                    }
                    accessPrefs.edit().putString("access_list_json", newList.toString()).apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val qIntent = Intent("QUARANTINE_UPDATED")
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(context).sendBroadcast(qIntent)
    }
}