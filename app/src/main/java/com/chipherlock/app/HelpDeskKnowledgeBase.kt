package com.chipherlock.app

// ═══════════════════════════════════════════════════════════════════════════
// HelpDeskKnowledgeBase.kt
//
// Offline knowledge base for the CHIPHERLOCK Help Desk.
// Used when Groq is unavailable OR when a user's question matches a topic
// exactly via keyword search — instant answer, no API call needed.
//
// Structure:
//   Each Article has a title, a list of searchKeywords, and a body string.
//   The body uses plain text with section markers (──) for readability.
//   HelpDeskFragment searches keywords case-insensitively against the
//   user's typed query before falling back to Groq.
// ═══════════════════════════════════════════════════════════════════════════

object HelpDeskKnowledgeBase {

    data class Article(
        val id: String,
        val category: String,
        val title: String,
        val keywords: List<String>,
        val body: String
    )

    val articles: List<Article> = listOf(

        // ── SMS SHIELD ───────────────────────────────────────────────────────

        Article(
            id       = "sms-001",
            category = "SMS Shield",
            title    = "How does the SMS scam scanner work?",
            keywords = listOf("scan", "scanner", "sms", "how", "work", "detect", "scam", "paano", "gumagana", "detection"),
            body     = """
📱 HOW THE SMS SCANNER WORKS
──────────────────────────────
CHIPHERLOCK scans every incoming SMS using two layers:

1. KEYWORD ENGINE (instant, offline)
   Checks the message against a library of known scam patterns sourced from BSP, NTC, and GSMA advisories. Catches common GCash/Maya fraud, phishing links, fake government messages, and more.

2. AI CHECK (online, Groq / Llama 3.3)
   A powerful AI model reads the full message and scores it from 0 to 100. It understands Filipino, Tagalog, Bisaya, Ilocano, and other Philippine languages. It can catch social engineering scams that have no obvious keywords — like fake job offers or romance scams written in plain conversational text.

FINAL SCORE
   The two scores are combined: 40% keyword + 60% AI.
   • 0–39  → LOW RISK 🟢 (message goes to Inbox)
   • 40–69 → MEDIUM RISK 🟠 (message goes to Scams tab)
   • 70+   → HIGH RISK 🔴 (message goes to Scams tab or Q-Zone if Auto-QZ is ON)

The scanner runs in the background even when the app is closed.
            """.trimIndent()
        ),

        Article(
            id       = "sms-002",
            category = "SMS Shield",
            title    = "What is the Q-Zone?",
            keywords = listOf("qzone", "q-zone", "quarantine", "quarantined", "held", "locked", "blocked", "release", "kulungan"),
            body     = """
🔒 WHAT IS THE Q-ZONE?
──────────────────────────────
The Q-Zone (Quarantine Zone) is a holding area for HIGH-risk messages when Auto-Quarantine is turned ON.

Messages in Q-Zone:
  • Are NOT deleted — they are safely stored
  • Cannot be read without your confirmation
  • Show the full risk score and breakdown before you decide

HOW TO RELEASE A MESSAGE
  1. Open the Q-Zone from the category drawer
  2. Tap a message to see its risk score breakdown
  3. Tap "Release to Inbox" at the bottom
  4. A dialog will ask you to confirm:
     – "Release + Add to Allow List" → sender trusted forever
     – "Release Only" → one-time release, logged in History
     – "Keep in Q-Zone" → nothing changes

Q-Zone is different from the Scams tab. Scams tab shows flagged messages you can read freely. Q-Zone is for HIGH-risk messages that need deliberate action to unlock.
            """.trimIndent()
        ),

        Article(
            id       = "sms-003",
            category = "SMS Shield",
            title    = "How do I manage the Access List?",
            keywords = listOf("access list", "allow", "block", "whitelist", "blacklist", "trusted", "sender", "number", "manage", "add", "remove", "puti", "itim"),
            body     = """
📋 MANAGING THE ACCESS LIST
──────────────────────────────
The Access List controls how CHIPHERLOCK treats specific senders.

ALLOW LIST ✅
  Messages from allowed senders skip scanning entirely and go straight to your Inbox. Use this for senders you fully trust — family members, your employer, known contacts.

BLOCK LIST 🚫
  Messages from blocked senders are silently dropped. They never appear anywhere in the app. Use this for persistent spam numbers.

HOW TO ADD A SENDER
  In the SMS tab → open a message → tap the three-dot menu (⋮) → "Add to Allow List" or "Add to Block List".

  You can also release a Q-Zone message and choose "Release + Add to Allow List" — the sender is automatically trusted.

HOW TO VIEW AND EDIT
  In the SMS tab → open the category drawer → scroll to "ACCESS LIST" section → tap Manage.

IMPORTANT: Adding to the Allow List does NOT mean CHIPHERLOCK ignores the message content forever. It only skips the scoring step. Suspicious URLs in messages from allowed senders are still visible to you.
            """.trimIndent()
        ),

        Article(
            id       = "sms-004",
            category = "SMS Shield",
            title    = "What is the Banking tab?",
            keywords = listOf("banking", "bank", "bdo", "bpi", "gcash", "maya", "verified", "sender", "financial", "tab"),
            body     = """
🏦 THE BANKING TAB
──────────────────────────────
The Banking tab shows messages only from verified Philippine financial institutions — banks, e-wallets, telcos, and couriers.

VERIFIED SENDERS INCLUDE:
  Banks: BDO, BPI, Metrobank, Unionbank, PNB, RCBC, Landbank, and more
  E-wallets: GCash, Maya, GrabPay, Coins.ph
  Telcos: Globe, Smart, TM, TNT, DITO
  Couriers: LBC, J&T, NinjaVan, 2GO

ANTI-SPOOFING PROTECTION
  Only alphanumeric sender IDs (like "BDO" or "GCASH") appear here. A scammer who sends from a regular phone number (+63912345678) while pretending to be BDO will NOT appear in the Banking tab — their message goes to the Scams tab instead.

REMEMBER: Real banks NEVER ask for your OTP, PIN, or password via SMS. If a message in the Banking tab contains a link or asks for credentials, treat it as a scam.
            """.trimIndent()
        ),

        Article(
            id       = "sms-005",
            category = "SMS Shield",
            title    = "Why is a legitimate message being flagged as a scam?",
            keywords = listOf("false positive", "legitimate", "flagged", "wrong", "mistake", "not scam", "legit", "mali", "tama", "bakit"),
            body     = """
⚠️ LEGITIMATE MESSAGE FLAGGED?
──────────────────────────────
Sometimes a real message can score MEDIUM or HIGH. Here's why and what to do:

COMMON REASONS
  • The message contains words that overlap with scam patterns (e.g. "verify your account", "click here", "limited time")
  • The sender number looks unusual (foreign country code, short code)
  • The message is in an informal style that the AI doesn't recognise as safe

WHAT TO DO
  1. Read the score breakdown — it shows exactly which words or patterns triggered the flag
  2. If you're confident the message is legitimate, tap "Release" (if in Q-Zone) or use the menu to "Add to Allow List"
  3. Future messages from the same sender will skip scanning

WHY WE LEAN TOWARD CAUTION
  CHIPHERLOCK is tuned to catch scams aggressively. Missing a real scam is more harmful than flagging a legitimate message. The score breakdown and your own judgment are the final filter.
            """.trimIndent()
        ),

        // ── VAULT ────────────────────────────────────────────────────────────

        Article(
            id       = "vault-001",
            category = "Vault",
            title    = "How does the Vault work?",
            keywords = listOf("vault", "password", "store", "save", "encrypted", "how", "paano", "safe", "secure", "manager"),
            body     = """
🔐 HOW THE VAULT WORKS
──────────────────────────────
The CHIPHERLOCK Vault is an encrypted password manager stored entirely on your device. No data is sent to any server.

ENCRYPTION
  Your vault data is protected by AES-256-GCM encryption — the same standard used by banks. Your master password is never stored; instead it is used to derive an encryption key using Argon2id, a memory-hard algorithm that makes brute-force attacks extremely slow.

TWO WAYS TO UNLOCK
  1. Biometric (fingerprint / face) — fastest, most convenient
  2. Master password — fallback if biometric fails or is unavailable

AUTOFILL
  Once CHIPHERLOCK is set as your Autofill provider (Settings → Passwords → CHIPHERLOCK), it will offer to fill your saved credentials directly into other apps — GCash, BDO, Facebook, etc.

FIRST TIME SETUP
  Tap the Vault tab → you will be prompted to create a master password. Choose something strong — it CANNOT be reset if forgotten.
            """.trimIndent()
        ),

        Article(
            id       = "vault-002",
            category = "Vault",
            title    = "I forgot my master password. What do I do?",
            keywords = listOf("forgot", "forget", "password", "lost", "reset", "recover", "nakalimutan", "hindi", "matandaan"),
            body     = """
😟 FORGOT YOUR MASTER PASSWORD
──────────────────────────────
Unfortunately, there is NO way to recover or reset a lost master password. This is by design — if recovery were possible, it would be a security vulnerability.

WHAT HAPPENS TO YOUR DATA
  If you cannot remember your master password AND biometric unlock is not working, your vault data cannot be accessed. The data remains on the device but is unrecoverable without the password.

WHAT YOU CAN DO
  Option 1: Try every password variation you might have used (case variations, added numbers, etc.)

  Option 2: Wipe the vault and start fresh.
    → Report tab → scroll to "Remote Control" → "Wipe Vault"
    → This permanently deletes the encrypted data and lets you create a new vault

PREVENTION FOR THE FUTURE
  • Write your master password on paper and store it in a safe place
  • Use a password you can remember but others cannot guess
  • The Vault generates strong passwords for other services — your master password should be something only you know
            """.trimIndent()
        ),

        Article(
            id       = "vault-003",
            category = "Vault",
            title    = "What is the Decoy Vault?",
            keywords = listOf("decoy", "honey", "fake", "duress", "forced", "coercion", "canary", "trap", "dummy"),
            body     = """
🍯 THE DECOY VAULT
──────────────────────────────
The Decoy Vault is a hidden safety feature for situations where someone forces you to open your vault under duress.

HOW IT WORKS
  There are TWO vaults on your device. They look identical — same interface, same appearance, indistinguishable on disk. One is your real vault. One is the decoy.

  The decoy contains realistic-looking but fake Philippine credentials (GCash, BDO, Shopee, Gmail, Facebook) with plausible Filipino names and passwords.

CANARY TRAP
  Some decoy entries are "canary" credentials. If anyone tries to use a canary password (e.g. types it into GCash), CHIPHERLOCK immediately sends you a security alert identifying which app tried to use it.

  This means even if someone steals your phone and opens the decoy vault, you will know the moment they try to use the fake credentials.

NOTE: The decoy vault is an advanced safety net. In an emergency, always prioritise your personal safety over your data.
            """.trimIndent()
        ),

        // ── NETWORK ──────────────────────────────────────────────────────────

        Article(
            id       = "net-001",
            category = "Network",
            title    = "How does the Network Scanner work?",
            keywords = listOf("network", "wifi", "scanner", "scan", "evil twin", "threat", "safe", "nearby", "wireless", "internet"),
            body     = """
📶 HOW THE NETWORK SCANNER WORKS
──────────────────────────────
The Network Scanner checks all nearby WiFi networks for security threats.

WHAT IT DETECTS

  🔴 THREAT — Evil Twin Attack
     Two or more access points share the same network name. One of them may be a fake hotspot set up by an attacker to intercept your traffic. Never connect to these.

  🟡 CAUTION — Suspicious Name
     Network names containing words like "Free", "Public", "Guest", or "Open" are often unencrypted and monitored. Avoid logging into banking or email on these.

  🟡 CAUTION — Hidden SSID
     The network does not broadcast its name. Verify with the location staff before connecting.

  🟡 CAUTION — Weak Signal Anomaly
     Very weak signal from a distant access point can indicate a spoofed or rogue AP.

  🟢 SAFE
     No threats detected. Still use common sense — always prefer your mobile data for sensitive transactions.

HOW TO USE
  Open the Network tab → tap "SCAN NEARBY NETWORKS" → results appear color-coded within seconds.
            """.trimIndent()
        ),

        // ── REPORT ───────────────────────────────────────────────────────────

        Article(
            id       = "report-001",
            category = "Report",
            title    = "What does my Security Score mean?",
            keywords = listOf("score", "report", "security", "rating", "overall", "percentage", "meaning", "ibig sabihin", "marka"),
            body     = """
📊 YOUR SECURITY SCORE
──────────────────────────────
Your Security Score (0–100) reflects your overall digital security posture. It is calculated from three factors:

  PASSWORD HEALTH (40% weight)
     Based on how strong your saved vault passwords are. Weak or reused passwords lower this score significantly.

  THREATS BLOCKED (25% weight)
     Based on how many scam messages were detected this week.
     • 0 threats  → 100
     • 1–2 threats → 90
     • 3–5 threats → 80
     • 6+ threats  → 70
     (More threats detected means more scam activity targeting you — not a reflection of your behaviour)

  LESSONS COMPLETED (35% weight)
     Based on how many Help Desk articles or topics you have read in this tab.

SCORE LABELS
  85–100 → EXCELLENT
  70–84  → GOOD
  50–69  → FAIR
  0–49   → NEEDS ATTENTION

HOW TO IMPROVE
  • Add your real passwords to the Vault and let CHIPHERLOCK generate strong ones
  • Read Help Desk articles on security topics
  • The threats score improves automatically as the scanner protects you
            """.trimIndent()
        ),

        Article(
            id       = "report-002",
            category = "Report",
            title    = "What is the Remote Lock and Wipe?",
            keywords = listOf("remote", "lock", "wipe", "lost", "stolen", "phone", "nawala", "ninakaw", "kill", "switch", "erase"),
            body     = """
🚨 REMOTE LOCK AND WIPE
──────────────────────────────
If your phone is lost or stolen, the Report tab gives you two emergency controls:

REMOTE LOCK
  Locks the vault immediately so no one can access your passwords even if they bypass the screen lock. Your data is NOT deleted — you can unlock again when you recover the phone.

WIPE VAULT
  Permanently deletes all vault data. This CANNOT be undone. Use only if you are certain you will not recover the phone and you want to ensure your passwords cannot be extracted.

HOW IT WORKS
  CHIPHERLOCK checks a secure revocation signal periodically. When you trigger a lock or wipe from a trusted device, the signal updates and CHIPHERLOCK enforces it the next time the phone connects to the internet (within 24 hours).

  The 24-hour offline grace period means the phone must connect to the internet at least once for the command to take effect.

IMPORTANT: Always confirm your identity before using these controls. Both actions require re-authentication for security.
            """.trimIndent()
        ),

        // ── GENERAL SECURITY ─────────────────────────────────────────────────

        Article(
            id       = "sec-001",
            category = "Security Tips",
            title    = "How do I spot a GCash or bank scam?",
            keywords = listOf("gcash", "bank", "scam", "spot", "identify", "fake", "otp", "transfer", "huwag", "magpadala", "beware"),
            body     = """
💳 SPOTTING GCASH AND BANK SCAMS
──────────────────────────────
These are the most common financial scams in the Philippines. Know the signs:

RED FLAGS 🔴
  • Any SMS asking for your OTP, PIN, or password — REAL banks and GCash NEVER ask for this
  • A link in the message (e.g. gcash-secure.online, bdo-verify.xyz) — REAL apps never need you to click a link to verify
  • Urgent language: "Your account will be locked in 24 hours", "Act now"
  • Promises of prizes, cashback, or refunds you never applied for
  • A sender phone number claiming to be a bank (real bank SMS comes from alphanumeric short codes)

SAFE PATTERNS ✅
  Real GCash: "You received PHP 500.00 from 09**5****23. Ref: 1234567. Available balance: PHP 1,500.00" — no link, no request
  Real BDO: "Your BDO acct ending 1234 was debited PHP 2,500.00 on May 16. Not you? Call 889-10000" — callback number, no link

WHAT TO DO IF YOU RECEIVE A SUSPICIOUS MESSAGE
  1. Do NOT click any link
  2. Do NOT reply with any information
  3. Call your bank directly using the number on the back of your card or their official website
  4. Report the sender to CHIPHERLOCK's Block List
            """.trimIndent()
        ),

        Article(
            id       = "sec-002",
            category = "Security Tips",
            title    = "What are common scam types in the Philippines?",
            keywords = listOf("types", "kinds", "list", "common", "scam", "philippines", "pilipinas", "lagay", "uri", "phishing", "vishing", "smishing"),
            body     = """
🇵🇭 COMMON SCAM TYPES IN THE PHILIPPINES
──────────────────────────────
CHIPHERLOCK detects all of these:

💳 Financial Fraud — Fake GCash/Maya/bank alerts asking for OTP or login
🔗 Phishing Link — Fake website links disguised as real bank/government sites
🏛️ Gov't Impersonation — Fake SSS, PhilHealth, DSWD cash aid, NBI summons
🎰 Prize/Lottery — "You won! Claim your prize at this link"
💼 Job/Task Scam — "Work from home, earn PHP 5,000/day liking posts"
📦 Parcel/Delivery — "Your package is held, pay redelivery fee here"
💑 Romance Scam — Stranger builds relationship then asks for money
🆘 Fake Emergency — "Nandito na ako sa ospital/presinto, send GCash"
💔 Sextortion — Threats to release intimate photos unless paid
🔪 Extortion/Kidnap — "Hawak namin ang pamilya mo, magbayad ka"
💰 Loan Scam — "Fast approval, no collateral, apply now"
💱 Crypto/Pig Butchering — Fake investment group with "guaranteed returns"
🛒 Marketplace Scam — Fake Shopee/Lazada sellers, bogus escrow
📲 SIM Swap — Asking you to confirm details "for SIM registration"

If you receive any of these, report the sender and do not engage.
            """.trimIndent()
        ),

        Article(
            id       = "sec-003",
            category = "Security Tips",
            title    = "How do I create a strong password?",
            keywords = listOf("strong", "password", "create", "generate", "tips", "good", "gumawa", "password", "matibay", "secure"),
            body     = """
🔑 CREATING A STRONG PASSWORD
──────────────────────────────
CHIPHERLOCK can generate strong passwords for you automatically — tap the + button in the Vault and use the Generate option.

IF YOU WANT TO CREATE ONE YOURSELF:

GOOD PASSWORD RULES
  ✅ At least 12 characters (16+ is better)
  ✅ Mix of uppercase, lowercase, numbers, and symbols
  ✅ No personal info (birthdate, name, phone number)
  ✅ No dictionary words (even with letter substitutions like p@ssw0rd — these are cracked easily)
  ✅ Different password for every account

EXAMPLES OF WEAK PASSWORDS ❌
  password123, iloveyou, 123456, abc123, juan2024, manila2024!

EXAMPLES OF STRONG PASSWORDS ✅
  Kf#9mQpL2!xR (random, 12 chars)
  correct-horse-battery-staple (passphrase, long)

REMEMBERING PASSWORDS
  You don't have to. Save them in the CHIPHERLOCK Vault and use biometric autofill. You only need to remember ONE strong master password.
            """.trimIndent()
        ),

        Article(
            id       = "sec-004",
            category = "Security Tips",
            title    = "Is it safe to use public WiFi?",
            keywords = listOf("public wifi", "wifi", "safe", "danger", "coffee shop", "mall", "library", "open network", "ligtas", "delikado"),
            body     = """
📡 PUBLIC WIFI SAFETY
──────────────────────────────
Public WiFi is convenient but risky. Here is how to stay safe:

WHAT THE RISKS ARE
  • Attackers can set up "evil twin" hotspots with the same name as the coffee shop or mall WiFi
  • On unsecured networks, others can see your unencrypted traffic
  • Logging into banking or GCash on public WiFi can expose your credentials

CHIPHERLOCK'S NETWORK SCANNER helps you detect evil twin attacks before you connect.

RULES FOR PUBLIC WIFI
  ✅ Use mobile data (LTE/5G) for banking, GCash, and email
  ✅ If you must use public WiFi, connect through a VPN
  ✅ Verify the network name with staff before connecting (e.g. "SM Free WiFi" vs "SM_FreeWiFi")
  ✅ Log out of accounts when done on a shared connection
  ❌ Never type passwords on public WiFi without a VPN
  ❌ Never connect to unnamed or hidden networks you don't recognise
            """.trimIndent()
        ),

        // ── APP USAGE ────────────────────────────────────────────────────────

        Article(
            id       = "app-001",
            category = "App Help",
            title    = "How do I set CHIPHERLOCK as my default SMS app?",
            keywords = listOf("default", "sms", "app", "set", "how", "itakda", "pangunahin", "messaging", "receive"),
            body     = """
📲 SETTING CHIPHERLOCK AS DEFAULT SMS APP
──────────────────────────────
To get full scanning capability — including the ability to intercept and quarantine HIGH-risk messages — CHIPHERLOCK needs to be your default SMS app.

HOW TO SET IT
  1. Open your phone's Settings
  2. Go to Apps (or Application Manager)
  3. Tap Default Apps
  4. Tap SMS App (or Messaging App)
  5. Select CHIPHERLOCK

  Alternatively: when CHIPHERLOCK first asks you to set it as default, tap "Set as Default" on the prompt.

WHAT CHANGES WHEN IT IS DEFAULT
  • CHIPHERLOCK receives ALL incoming SMS first, scans them, then routes them
  • HIGH-risk messages can be quarantined before you see them
  • The app can write incoming messages to your phone's SMS inbox

WHAT STAYS THE SAME
  • Your existing messages are NOT deleted or modified
  • You can switch back to your previous SMS app at any time
  • All your contacts and message history remain intact

NOTE: If CHIPHERLOCK is NOT the default app, it still monitors SMS in the background but cannot quarantine messages — it can only alert you after delivery.
            """.trimIndent()
        ),

        Article(
            id       = "app-002",
            category = "App Help",
            title    = "How do I enable the Autofill service?",
            keywords = listOf("autofill", "fill", "automatic", "login", "enable", "setup", "i-setup", "password fill", "credentials"),
            body     = """
🔑 ENABLING CHIPHERLOCK AUTOFILL
──────────────────────────────
CHIPHERLOCK can automatically fill your saved passwords into other apps (GCash, BDO, Shopee, Facebook, etc.).

HOW TO ENABLE
  1. Open your phone's Settings
  2. Search for "Autofill" or go to General Management → Passwords and Autofill
  3. Select Autofill Service
  4. Choose CHIPHERLOCK
  5. Tap OK on the permission prompt

HOW IT WORKS
  When you open a login screen in any app, CHIPHERLOCK will show a prompt at the bottom with your saved credentials for that app. Tap it to fill automatically.

SECURITY
  Autofill only fills credentials that match the app's package name. A fake app pretending to be GCash will NOT receive your real GCash password — CHIPHERLOCK checks the app identity first.

  The canary system is also active during autofill — if anyone tries to autofill a canary credential, you receive an immediate security alert.
            """.trimIndent()
        )
    )

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Returns the best matching article for the query, or null if no good match.
     * Matching is keyword-based: count how many keywords appear in the query.
     * An article needs at least 1 keyword match to be returned.
     */
    fun search(query: String): Article? {
        if (query.isBlank()) return null
        val q = query.lowercase()
        return articles
            .map { article ->
                val hits = article.keywords.count { kw -> q.contains(kw.lowercase()) }
                article to hits
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Returns all articles in a given category.
     */
    fun byCategory(category: String): List<Article> =
        articles.filter { it.category == category }

    /** All distinct categories in order. */
    val categories: List<String> by lazy {
        articles.map { it.category }.distinct()
    }
}