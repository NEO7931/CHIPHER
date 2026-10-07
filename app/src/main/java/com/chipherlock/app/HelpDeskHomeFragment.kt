package com.chipherlock.app

// ═══════════════════════════════════════════════════════════════════════════
// HelpDeskHomeFragment.kt — Phase 3.1 Add-on
//
// Default landing page of the Help Desk tab.
// Two sections:
//
//   1. HOW TO USE CHIPHERLOCK
//      Most common user questions — concise, collapsible cards.
//      Tapping a card expands the answer inline.
//
//   2. KNOW YOUR THREATS
//      Android attack types relevant to Filipino users.
//      Each card shows: attack name, plain-language explanation,
//      real-world Philippine context, and a cited source.
//      Sources are 100% real and open: OWASP, Kaspersky Securelist,
//      Check Point / Cyberint PH Threat Landscape 2025, BSP, TransUnion.
//
// Navigation:
//   This fragment lives inside HelpDeskFragment which hosts a TabLayout
//   with two tabs: HOME (this) and CHAT (the existing chat UI).
//
// ═══════════════════════════════════════════════════════════════════════════

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class HelpDeskHomeFragment : Fragment() {

    private lateinit var dp: (Int) -> Int

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        dp = { v -> (v * density).toInt() }
        return buildUI(ctx)
    }

    // ════════════════════════════════════════════════════════════════════════
    // ROOT UI
    // ════════════════════════════════════════════════════════════════════════

    private fun buildUI(ctx: Context): View {
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(80))
        }
        scroll.addView(root)

        // ── Section 1: How to Use CHIPHERLOCK ───────────────────────────────
        root.addView(buildSectionHeader(ctx, "📱 How to Use CHIPHERLOCK",
            "Most common questions from new users"))

        howToUseItems.forEach { item ->
            root.addView(buildCollapsibleCard(ctx, item.question, item.answer,
                accentColor = 0xFF1565C0.toInt()))
        }

        // ── Divider ──────────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(20); bottomMargin = dp(8) }
        })

        // ── Section 2: Know Your Threats ─────────────────────────────────────
        root.addView(buildSectionHeader(ctx, "⚠️ Know Your Threats",
            "Common Android attacks targeting Filipinos — with real sources"))

        // Notice about sources
        root.addView(TextView(ctx).apply {
            text = "All threat data below is sourced from open, verified security research. " +
                    "Tap the source link on any card to read the original report."
            textSize = 12f
            setTextColor(0xFF616161.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        threatItems.forEach { item ->
            root.addView(buildThreatCard(ctx, item))
        }

        return scroll
    }

    // ════════════════════════════════════════════════════════════════════════
    // SECTION HEADER
    // ════════════════════════════════════════════════════════════════════════

    private fun buildSectionHeader(ctx: Context, title: String, subtitle: String): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))

            addView(TextView(ctx).apply {
                text     = title
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF0D47A1.toInt())
            })
            addView(TextView(ctx).apply {
                text     = subtitle
                textSize = 12f
                setTextColor(0xFF757575.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); bottomMargin = dp(8) }
            })
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // COLLAPSIBLE FAQ CARD
    // ════════════════════════════════════════════════════════════════════════

    private fun buildCollapsibleCard(
        ctx: Context,
        question: String,
        answer: String,
        accentColor: Int
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation     = dp(2).toFloat()
            layoutParams  = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Accent bar on the left
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        row.addView(View(ctx).apply {
            setBackgroundColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(dp(4),
                LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val contentCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val questionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        val tvQ = TextView(ctx).apply {
            text     = question
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF212121.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvChevron = TextView(ctx).apply {
            text     = "▼"
            textSize = 12f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        questionRow.addView(tvQ)
        questionRow.addView(tvChevron)
        contentCol.addView(questionRow)

        val tvA = TextView(ctx).apply {
            text       = answer
            textSize   = 13f
            setTextColor(0xFF424242.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        contentCol.addView(tvA)
        row.addView(contentCol)
        card.addView(row)

        // Toggle on tap
        card.setOnClickListener {
            val expanded = tvA.visibility == View.VISIBLE
            tvA.visibility = if (expanded) View.GONE else View.VISIBLE
            tvChevron.text = if (expanded) "▼" else "▲"
        }

        return card
    }

    // ════════════════════════════════════════════════════════════════════════
    // THREAT CARD (with source citation)
    // ════════════════════════════════════════════════════════════════════════

    private fun buildThreatCard(ctx: Context, item: ThreatItem): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation    = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        // Severity color bar
        val barColor = when (item.severity) {
            Severity.CRITICAL -> 0xFFB71C1C.toInt()
            Severity.HIGH     -> 0xFFE53935.toInt()
            Severity.MEDIUM   -> 0xFFF57C00.toInt()
            Severity.LOW      -> 0xFF388E3C.toInt()
        }
        row.addView(View(ctx).apply {
            setBackgroundColor(barColor)
            layoutParams = LinearLayout.LayoutParams(dp(5),
                LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Title + severity badge row
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text     = item.emoji + "  " + item.name
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF212121.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(ctx).apply {
            text     = item.severity.label
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(barColor)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(titleRow)

        // Plain-language what it is
        col.addView(TextView(ctx).apply {
            text     = item.whatItIs
            textSize = 13f
            setTextColor(0xFF424242.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        // Philippine context box
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFF8E1.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }

            addView(TextView(ctx).apply {
                text     = "🇵🇭  In the Philippines:"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFFE65100.toInt())
            })
            addView(TextView(ctx).apply {
                text     = item.phContext
                textSize = 12f
                setTextColor(0xFF5D4037.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        })

        // What to do
        col.addView(TextView(ctx).apply {
            text     = "✅  What to do: ${item.whatToDo}"
            textSize = 12f
            setTextColor(0xFF1B5E20.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })

        // Source citation — tappable
        col.addView(TextView(ctx).apply {
            text     = "📄  Source: ${item.sourceName}"
            textSize = 11f
            setTextColor(0xFF1565C0.toInt())
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                }
            }
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        })

        row.addView(col)
        card.addView(row)
        return card
    }

    // ════════════════════════════════════════════════════════════════════════
    // DATA — HOW TO USE
    // ════════════════════════════════════════════════════════════════════════

    data class FaqItem(val question: String, val answer: String)

    private val howToUseItems = listOf(
        FaqItem(
            "How do I set CHIPHERLOCK as my default SMS app?",
            "Go to your phone's Settings → Apps → Default Apps → SMS App → select CHIPHERLOCK.\n\n" +
                    "Why it matters: CHIPHERLOCK can only quarantine HIGH-risk messages before you see them when it is your default SMS app. Without this, it can still alert you  but cannot block delivery."
        ),
        FaqItem(
            "How do I read my SMS scan results?",
            "Every message gets a risk score from 0–100:\n\n" +
                    "🟢 LOW (0–39) → Goes to your Inbox. Likely safe.\n" +
                    "🟠 MEDIUM (40–69) → Goes to Scams tab. Read with caution.\n" +
                    "🔴 HIGH (70–100) → Goes to Scams tab or Q-Zone if Auto-QZ is ON.\n\n" +
                    "Tap any message to see the full score breakdown  it shows exactly which words or patterns triggered the flag."
        ),
        FaqItem(
            "What is the Q-Zone and how do I release a message?",
            "The Q-Zone is a quarantine zone for HIGH-risk messages. They are NOT deleted  just held.\n\n" +
                    "To release: open Q-Zone → tap the message → tap 'Release to Inbox' → choose:\n" +
                    "• Release + Allow List → trust sender forever\n" +
                    "• Release Only → one-time release, logged in History\n" +
                    "• Keep in Q-Zone → nothing changes"
        ),
        FaqItem(
            "How do I add a sender to the Allow or Block list?",
            "Open any message → tap the ⋮ menu → choose 'Add to Allow List' or 'Add to Block List'.\n\n" +
                    "You can also manage the full list from the SMS category drawer → ACCESS LIST section.\n\n" +
                    "Allow List: skips scanning, goes straight to Inbox.\n" +
                    "Block List: messages are silently dropped  they won't appear anywhere."
        ),
        FaqItem(
            "How do I save a password to the Vault?",
            "Tap the Vault tab (🔒) → tap the + button → fill in the app/website name, username, and password.\n\n" +
                    "Or let CHIPHERLOCK generate one for you  tap Generate inside the password field.\n\n" +
                    "First time? You'll be asked to create a master password. Choose something strong  it cannot be reset if forgotten."
        ),
        FaqItem(
            "How does autofill work?",
            "Once enabled, CHIPHERLOCK automatically offers to fill your saved passwords when you open any login screen.\n\n" +
                    "To enable: Settings → Passwords (or General Management) → Autofill Service → CHIPHERLOCK.\n\n" +
                    "Security note: CHIPHERLOCK checks the app's identity before filling. A fake GCash app will NOT receive your real GCash password."
        ),
        FaqItem(
            "How do I scan nearby WiFi networks?",
            "Tap the Network tab (📶) → tap 'SCAN NEARBY NETWORKS'.\n\n" +
                    "Results appear with colour codes:\n" +
                    "🔴 THREAT  possible evil twin attack (two networks same name)\n" +
                    "🟡 CAUTION  suspicious name, hidden SSID, or weak signal anomaly\n" +
                    "🟢 SAFE  no threats detected\n\n" +
                    "Even on a SAFE network, use mobile data for banking transactions."
        ),
        FaqItem(
            "What does my Security Score mean?",
            "Your score (0–100) in the Report tab is calculated from:\n\n" +
                    "• Password Health (40%)  strength of your saved vault passwords\n" +
                    "• Threats Blocked (25%)  scam messages detected this week\n" +
                    "• Help Desk Articles Read (35%)  how many of the 18 articles you've opened\n\n" +
                    "85–100 = Excellent · 70–84 = Good · 50–69 = Fair · Below 50 = Needs Attention"
        ),
        FaqItem(
            "What is the Decoy Vault?",
            "The Decoy Vault is a hidden safety feature. There are TWO vaults on your device  one real, one fake. They look identical.\n\n" +
                    "The fake vault contains realistic but made-up Filipino credentials (GCash, BDO, Shopee, etc.).\n\n" +
                    "If someone forces you to open your phone, show them the decoy. Some decoy entries are 'canary' credentials  if anyone tries to use them, CHIPHERLOCK sends you an alert immediately identifying which app tried to use it."
        ),
        FaqItem(
            "A legitimate message was flagged as a scam. What do I do?",
            "This can happen if a real message contains words that overlap with scam patterns.\n\n" +
                    "Steps:\n" +
                    "1. Read the score breakdown (it shows exactly what triggered the flag)\n" +
                    "2. If you're confident it's safe, tap Release (from Q-Zone) or use ⋮ → Add to Allow List\n" +
                    "3. Future messages from that sender will skip scanning\n\n" +
                    "CHIPHERLOCK intentionally leans toward caution. Your own judgment is always the final filter."
        )
    )

    // ════════════════════════════════════════════════════════════════════════
    // DATA — THREATS (100% sourced from open security research)
    // ════════════════════════════════════════════════════════════════════════

    enum class Severity(val label: String) {
        CRITICAL("CRITICAL"),
        HIGH("HIGH"),
        MEDIUM("MEDIUM"),
        LOW("LOW")
    }

    data class ThreatItem(
        val emoji:       String,
        val name:        String,
        val severity:    Severity,
        val whatItIs:    String,
        val phContext:   String,
        val whatToDo:    String,
        val sourceName:  String,
        val sourceUrl:   String
    )

    private val threatItems = listOf(

        ThreatItem(
            emoji    = "📩",
            name     = "Smishing (SMS Phishing)",
            severity = Severity.CRITICAL,
            whatItIs = "Scammers send fake text messages pretending to be your bank, GCash, the SSS, LTO, or a courier. The goal is to trick you into clicking a link or sharing your OTP.",
            phContext = "Phishing sites targeting Filipinos jumped 423% in 2025  from 731 to 3,824 sites. Smishing is now the #1 cyber threat in the Philippines. A 2024 POGO crackdown displaced centralized scam hubs; rogue 'text blasters' (portable fake cell towers costing ₱20,000–₱40,000) are now being sold to small-time criminals. Smishing + phishing accounted for 45% and 41% of all fraud attempts reported by Filipinos in Q4 2024.",
            whatToDo = "Never click SMS links from unknown senders. Never share your OTP. CHIPHERLOCK's SMS scanner automatically flags these  check your Scams tab.",
            sourceName = "Check Point / Cyberint Philippine Threat Landscape Report 2025 · TransUnion PH Consumer Fraud Survey Q4 2024",
            sourceUrl  = "https://cyberint.com/blog/threat-intelligence/philippine-threat-landscape-report-2024-2025/"
        ),

        ThreatItem(
            emoji    = "🏦",
            name     = "Banking Trojans",
            severity = Severity.CRITICAL,
            whatItIs = "Malware disguised as a normal app (like a fake update, game, or tracking app) that secretly steals your banking login and GCash credentials in the background.",
            phContext = "Kaspersky reported a 196% surge in banking Trojan attacks on Android in 2024  from 420,000 to 1,242,000 attacks globally. The Wroba and Gigabud families specifically targeted Southeast Asian users including the Philippines. Gigabud was found mimicking banking and loan apps, capable of screen-recording and bypassing 2-factor authentication.",
            whatToDo = "Only install apps from the official Google Play Store. Never install APK files sent via SMS or chat. Keep Google Play Protect enabled.",
            sourceName = "Kaspersky Mobile Malware Threat Landscape 2024 (Securelist)",
            sourceUrl  = "https://securelist.com/mobile-threat-report-2024/115494/"
        ),

        ThreatItem(
            emoji    = "📶",
            name     = "Evil Twin WiFi Attack",
            severity = Severity.HIGH,
            whatItIs = "An attacker sets up a fake WiFi hotspot with the same name as a real network (e.g. 'SM Free WiFi'). When you connect, they can see everything you send  including login credentials.",
            phContext = "OWASP ranks Man-in-the-Middle attacks (which include evil twins) in its Mobile Top 10. In a real documented attack, Bank of America users were targeted via rogue WiFi that forced browsers to use an older, breakable encryption standard. Malls, coffee shops, and airports are common locations for this attack in the Philippines.",
            whatToDo = "Always use CHIPHERLOCK's Network Scanner before connecting to public WiFi. Use mobile data for GCash and banking. A VPN adds another layer of protection on public networks.",
            sourceName = "OWASP Mobile Top 10 2024  M3: Insecure Communication",
            sourceUrl  = "https://owasp.org/www-project-mobile-top-10/"
        ),

        ThreatItem(
            emoji    = "🔁",
            name     = "SIM Swap Attack",
            severity = Severity.HIGH,
            whatItIs = "A criminal calls your telco (Globe, Smart, DITO) pretending to be you, and convinces them to transfer your phone number to a SIM card the criminal owns. They then receive all your OTPs  and can access your GCash, bank, and email accounts.",
            phContext = "The BSP reported approximately 35,000 phishing complaints in 2024 resulting in ₱1.17 billion in losses  many of which involved SIM swap as the enabler for OTP bypass. The Philippine SIM Registration Act (RA 11934) was partly introduced to make SIM swaps harder, but scammers adapt using stolen government IDs from data breaches.",
            whatToDo = "Never share your government ID details in response to unsolicited calls or messages. Enable account notifications on your bank and GCash. Contact your telco immediately if your SIM suddenly loses signal.",
            sourceName = "BSP 2024 Phishing Complaint Report · Respicio & Co. Philippine Cybercrime Legal Analysis 2025",
            sourceUrl  = "https://www.respicio.ph/commentaries/disputing-and-reporting-phishing-scams-in-the-philippines"
        ),

        ThreatItem(
            emoji    = "📦",
            name     = "Fake App / Trojan Dropper",
            severity = Severity.HIGH,
            whatItIs = "An app that looks legitimate but secretly installs additional malware in the background. Common disguises: fake system updates, fake tracking apps, modified versions of popular apps (like a fake WhatsApp or GCash).",
            phContext = "In Q1 2024, Kaspersky detected a sharp increase in Trojan dropper activity specifically in the Asia-Pacific region, driven by the Wroba family. Modified WhatsApp versions containing the Triada Trojan ranked 3rd in global mobile malware activity in 2024. Kaspersky also identified Filipino users as targets of the Hiddapp malware family, which silently displays ads and downloads additional malicious modules.",
            whatToDo = "Never install apps from outside the Google Play Store. Be suspicious of any app that asks for permissions it doesn't need  a flashlight app does not need access to your contacts.",
            sourceName = "Kaspersky IT Threat Evolution Q1 2024  Mobile Statistics (Securelist)",
            sourceUrl  = "https://securelist.com/it-threat-evolution-q1-2024-mobile-statistics/112750/"
        ),

        ThreatItem(
            emoji    = "🎣",
            name     = "Vishing (Voice Phishing)",
            severity = Severity.HIGH,
            whatItIs = "A scammer calls you on the phone pretending to be from BDO, BPI, GCash, the NBI, or even the PNP. They create urgency ('your account will be locked', 'you have a warrant') to trick you into giving your OTP, password, or personal information.",
            phContext = "Vishing is closely linked to smishing in the Philippines  scammers often send an SMS first, then call as a follow-up. The PNP Anti-Cybercrime Group lists vishing as one of the top cybercrime methods in its annual report. A 2024 global vishing study found 70% of organizations were exposed to simulated voice phishing, with 6.5% of employees disclosing sensitive information.",
            whatToDo = "Hang up and call back the institution directly using the number on the back of your card or their official website. Real banks and GCash will NEVER ask for your OTP over the phone.",
            sourceName = "Keepnet Labs Phishing Statistics 2026 · PNP-ACG 2024 Cybercrime Report",
            sourceUrl  = "https://keepnetlabs.com/blog/top-phishing-statistics-and-trends-you-must-know"
        ),

        ThreatItem(
            emoji    = "📷",
            name     = "QR Code Phishing (Quishing)",
            severity = Severity.MEDIUM,
            whatItIs = "A fake QR code that looks official (on a parking meter, restaurant table, or event poster) but leads to a phishing website that steals your credentials or installs malware.",
            phContext = "Microsoft Defender detections of QR phishing rose 146% from January to March 2026 globally. In the Philippines, QR codes are widely used for GCash payments, which makes them a growing attack surface. Attackers replace legitimate GCash QR codes in stores with their own, redirecting payments to criminal accounts.",
            whatToDo = "Always verify where a QR code leads before entering any information. For payments, confirm the merchant name shown on your screen before approving. Use official apps to scan QR codes rather than generic camera apps.",
            sourceName = "Microsoft Digital Defense Report 2025 · Keepnet Labs Phishing Statistics 2026",
            sourceUrl  = "https://keepnetlabs.com/blog/top-phishing-statistics-and-trends-you-must-know"
        ),

        ThreatItem(
            emoji    = "💰",
            name     = "Investment Scam Apps (Pig Butchering)",
            severity = Severity.HIGH,
            whatItIs = "Fake investment or trading apps that show you fake profits to gain your trust, then ask for larger deposits  before disappearing with your money. Often starts as a romance or friendship built over weeks on social media.",
            phContext = "Kaspersky identified 'Fakemoney'  a family of scam investment and payout apps  as the most active mobile threat of 2024 globally. In the Philippines, the NBI and PNP-ACG have conducted multiple raids on pig-butchering scam hubs in 2024, many operated by trafficked workers. These scams caused billions of pesos in losses to Filipino victims.",
            whatToDo = "Never invest through an app recommended by someone you met online. Legitimate investment platforms are registered with the SEC Philippines. Check the SEC's list of verified investment firms at sec.gov.ph.",
            sourceName = "Kaspersky Mobile Malware Threat Landscape 2024 (Securelist) · Cyberint PH Threat Landscape 2025",
            sourceUrl  = "https://securelist.com/mobile-threat-report-2024/115494/"
        ),

        ThreatItem(
            emoji    = "🔑",
            name     = "Credential Stuffing",
            severity = Severity.MEDIUM,
            whatItIs = "Criminals take usernames and passwords stolen from one data breach (e.g. a leaked Shopee or Facebook breach) and automatically try them on other services  GCash, BDO, email. If you reuse passwords, one breach unlocks everything.",
            phContext = "The Philippines experienced significant data breach incidents in 2024 across government, finance, and retail sectors according to the Cyberint PH Threat Landscape Report. Multiple breaches exposed millions of Filipino user credentials that are sold on the dark web and used for credential stuffing attacks on e-wallets and banking apps.",
            whatToDo = "Use a unique password for every account. CHIPHERLOCK's Vault can store and generate strong, unique passwords. Enable biometric login on GCash and your banking app as a second layer of protection.",
            sourceName = "Cyberint / Check Point Philippine Threat Landscape Report 2024–2025",
            sourceUrl  = "https://cyberint.com/blog/threat-intelligence/philippine-threat-landscape-report-2024-2025/"
        ),

        ThreatItem(
            emoji    = "🤖",
            name     = "AI-Powered Phishing",
            severity = Severity.HIGH,
            whatItIs = "Scammers now use AI to craft phishing messages that sound perfectly natural, are free of spelling errors, and are personalized to the victim. AI-generated phishing is 4.5x more effective than traditional phishing at tricking people into clicking.",
            phContext = "Microsoft's 2025 Digital Defense Report found that AI-automated phishing achieves a 54% click-through rate versus 12% for standard phishing  a 4.5x multiplier. In the Philippines, where smishing is already the dominant threat, AI is now being used to generate convincing Tagalog and Filipino-dialect messages that bypass both human and machine detection. This is the fastest-growing emerging threat in the PH threat landscape.",
            whatToDo = "Treat urgency and emotion in any message as a red flag  this is the primary tool AI phishing uses. Pause and call the institution directly before taking any action. CHIPHERLOCK's AI scanner is specifically tuned to detect AI-generated scam patterns.",
            sourceName = "Microsoft Digital Defense Report 2025 · Check Point PH Threat Landscape 2025",
            sourceUrl  = "https://www.microsoft.com/en-us/security/security-insider/microsoft-digital-defense-report-2025"
        )
    )
}