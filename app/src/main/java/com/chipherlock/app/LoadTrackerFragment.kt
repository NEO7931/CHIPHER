package com.chipherlock.app

// ---------------------------------------------------------------------------
// LoadTrackerFragment.kt  (Phase 4.1 — rebuilt on secure foundations)
//
// What this does:
//   1. Detects SIM(s) automatically via TelephonyManager / SubscriptionManager
//   2. Fires USSD balance check silently when user taps "Check Balance"
//   3. Displays parsed telco response (balance, promo, expiry, data)
//   4. Shows SMS spend history (encrypted, hashed recipients, 30-day retention)
//   5. No-promo warning when vault is unlocked and no promo detected
//
// What this does NOT do:
//   • Store USSD responses (display only — discarded on screen exit)
//   • Store raw recipient numbers (SHA-256 hash only)
//   • Store anything outside the Vault's AES-256-GCM encryption
// ---------------------------------------------------------------------------

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class LoadTrackerFragment : Fragment() {

    // ── Companion: called from SmsFragment.sendSmsTo() ──────────────────────

    companion object {
        /**
         * Called every time an SMS is confirmed sent through CHIPHERLOCK.
         * Logs a hashed, encrypted entry to load history.
         * Silently does nothing if vault is locked.
         */
        fun recordSmsSent(
            ctx: Context, recipient: String, promoId: String,
            promoName: String, costPesos: Double, telcoId: String
        ) {
            val entry = LoadHistoryManager.SmsSpendEntry(
                timestampMs   = System.currentTimeMillis(),
                recipientHash = LoadHistoryManager.hashRecipient(recipient),
                promoId       = promoId,
                promoName     = promoName,
                costPesos     = costPesos,
                telcoId       = telcoId
            )
            LoadHistoryManager.appendEntry(ctx, entry)
        }

        // Legacy call from earlier integration — maps to new secure function
        fun incrementSmsSent(ctx: Context) {
            val entry = LoadHistoryManager.SmsSpendEntry(
                timestampMs   = System.currentTimeMillis(),
                recipientHash = "unknown",
                promoId       = "unknown",
                promoName     = "Unknown",
                costPesos     = 0.0,
                telcoId       = "UNKNOWN"
            )
            LoadHistoryManager.appendEntry(ctx, entry)
        }

        @Suppress("unused")
        fun hasActivePromo(ctx: Context): Boolean =
            ctx.getSharedPreferences("load_tracker_prefs", Context.MODE_PRIVATE)
                .getString("active_promo_id", null) != null
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private lateinit var tvSim1Carrier:  TextView
    private lateinit var tvSim1Status:   TextView
    private lateinit var btnCheckSim1:   Button
    private lateinit var cardSim2:       LinearLayout
    private lateinit var tvSim2Carrier:  TextView
    private lateinit var tvSim2Status:   TextView
    private lateinit var btnCheckSim2:   Button
    private lateinit var cardResult:     LinearLayout
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvResultLabel:  TextView
    private lateinit var tvBalance:      TextView
    private lateinit var tvPromo:        TextView
    private lateinit var tvExpiry:       TextView
    private lateinit var tvData:         TextView
    private lateinit var tvRawFallback:  TextView
    private lateinit var llHistory:      LinearLayout
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var btnClearHist:   Button

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_load_tracker, container, false)
        buildUI(requireContext(), view.findViewById(R.id.loadTrackerContent))
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkPermissionsThenDetect()
    }

    override fun onResume() {
        super.onResume()
        loadHistorySection()
    }

    // ── UI construction ──────────────────────────────────────────────────────

    private fun buildUI(ctx: Context, root: LinearLayout) {
        // ── Header ──────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(CipherPalette.surfaceHigh)
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(Button(ctx).apply {
                text      = "← BACK"
                textSize  = 11f
                setTextColor(CipherPalette.textPrimary)
                backgroundTintList = android.content.res.ColorStateList
                    .valueOf(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(ctx, 8) }
                setOnClickListener { parentFragmentManager.popBackStack() }
            })
            addView(TextView(ctx).apply {
                text     = "Load Balance"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CipherPalette.textPrimary)
            })
        })

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 32))
        }
        root.addView(content)

        // ── SIM 1 card ──────────────────────────────────────────────────
        content.addView(buildSimCard(ctx, 1).also { card ->
            tvSim1Carrier = card.tag          as TextView
            tvSim1Status  = card.getTag(R.id.nav_vault)   as TextView
            btnCheckSim1  = card.getTag(R.id.nav_network) as Button
        })

        // ── SIM 2 card (hidden until dual SIM confirmed) ─────────────
        cardSim2 = buildSimCard(ctx, 2).also { card ->
            tvSim2Carrier = card.tag          as TextView
            tvSim2Status  = card.getTag(R.id.nav_vault)   as TextView
            btnCheckSim2  = card.getTag(R.id.nav_network) as Button
            card.visibility = View.GONE
        }
        content.addView(cardSim2)

        // ── Result card ──────────────────────────────────────────────────
        cardResult = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundedBg(ctx, CipherPalette.surface)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(ctx, 12) }
        }
        content.addView(cardResult)

        progressBar = ProgressBar(ctx).apply {
            visibility   = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity     = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(ctx, 8)
            }
        }
        cardResult.addView(progressBar)

        tvResultLabel = label(ctx,
            "Tap 'Check Balance' to query your live load from the network.",
            13f, "#555555")
        cardResult.addView(tvResultLabel)

        // Structured result fields (hidden until USSD response arrives)
        tvBalance = label(ctx, "", 28f, "#1A1A1A").also {
            it.typeface   = Typeface.DEFAULT_BOLD
            it.visibility = View.GONE
        }
        cardResult.addView(tvBalance)

        tvPromo = label(ctx, "", 14f, "#1976D2").also { it.visibility = View.GONE }
        cardResult.addView(tvPromo)

        tvExpiry = label(ctx, "", 13f, "#444444").also { it.visibility = View.GONE }
        cardResult.addView(tvExpiry)

        tvData = label(ctx, "", 13f, "#444444").also { it.visibility = View.GONE }
        cardResult.addView(tvData)

        tvRawFallback = label(ctx, "", 12f, "#666666").also { it.visibility = View.GONE }
        cardResult.addView(tvRawFallback)

        // ── SMS Spend History ────────────────────────────────────────────
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
            addView(TextView(ctx).apply {
                text      = "SMS Spend History"
                textSize  = 15f
                typeface  = Typeface.DEFAULT_BOLD
                setTextColor(CipherPalette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            btnClearHist = Button(ctx).apply {
                text      = "Clear"
                textSize  = 11f
                setTextColor(CipherPalette.danger)
                backgroundTintList = android.content.res.ColorStateList
                    .valueOf(Color.TRANSPARENT)
                setOnClickListener { confirmClearHistory(ctx) }
            }
            addView(btnClearHist)
        })

        tvHistoryEmpty = label(ctx,
            if (VaultKeyManager.isUnlocked())
                "No SMS sent through CHIPHERLOCK yet."
            else
                "Unlock the vault to view SMS history.",
            12f, "#888888")
        content.addView(tvHistoryEmpty)

        llHistory = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(llHistory)

        // ── Disclaimer ──────────────────────────────────────────────────
        content.addView(label(ctx,
            "Balance is fetched live from your telco via USSD. " +
                    "SMS history shows only messages sent through CHIPHERLOCK. " +
                    "Recipient identities are hashed — CHIPHERLOCK cannot recover them. " +
                    "History is automatically deleted after 30 days.",
            11f, "#999999").also { it.setPadding(0, dp(ctx, 16), 0, 0) })

    }

    private fun buildSimCard(ctx: Context, slot: Int): LinearLayout {
        val carrierTv = TextView(ctx).apply {
            text     = "Detecting SIM $slot…"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CipherPalette.textPrimary)
        }
        val statusTv = TextView(ctx).apply {
            text     = "—"
            textSize = 12f
            setTextColor(CipherPalette.textSecondary)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 12))
        }
        val checkBtn = Button(ctx).apply {
            text = "Check Balance"
            textSize = 13f
            setTextColor(CipherPalette.onPrimary)
            backgroundTintList = android.content.res.ColorStateList
                .valueOf(CipherPalette.primary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundedBg(ctx, CipherPalette.surface)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(ctx, 12) }
            addView(label(ctx, "SIM $slot", 10f, "#888888").also {
                it.typeface = Typeface.DEFAULT_BOLD
            })
            addView(carrierTv)
            addView(statusTv)
            addView(checkBtn)
            // Store views as tags for retrieval in buildUI()
            tag = carrierTv
            setTag(R.id.nav_vault,   statusTv)
            setTag(R.id.nav_network, checkBtn)
        }
    }

    // ── Permission gate ──────────────────────────────────────────────────────

    private fun checkPermissionsThenDetect() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            detectSims()
        } else {
            showPermissionExplanation()
        }
    }

    private fun showPermissionExplanation() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Phone Permission Needed")
            .setMessage(
                "CHIPHERLOCK needs Phone access to check your load balance " +
                        "directly from your telco via USSD (*143#, *123#).\n\n" +
                        "This does NOT allow the app to make calls or access your call log. " +
                        "It is used only when you tap 'Check Balance' — never in the background."
            )
            .setPositiveButton("I understand, continue") { _, _ ->
                requestPermissions(arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
                ), 201)
            }
            .setNegativeButton("Not now") { _, _ ->
                tvResultLabel.text =
                    "Phone permission is required to check live balance. " +
                            "You can grant it by tapping 'Check Balance' again."
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 201 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            detectSims()
        } else {
            tvResultLabel.text =
                "Permission denied. You can grant Phone access in " +
                        "Settings → Apps → CHIPHERLOCK → Permissions."
        }
    }

    // ── SIM detection ────────────────────────────────────────────────────────

    private fun detectSims() {
        val ctx       = context ?: return
        val telephony = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED) {

            val subManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as SubscriptionManager
            val subs = subManager.activeSubscriptionInfoList

            if (!subs.isNullOrEmpty() && subs.size >= 2) {
                val sub1     = subs[0]
                val carrier1 = sub1.carrierName?.toString() ?: "Unknown"
                tvSim1Carrier.text = carrier1
                tvSim1Status.text  = carrierLabel(carrier1)
                btnCheckSim1.setOnClickListener {
                    fireUssd(ctx, carrier1, sub1.subscriptionId)
                }

                val sub2     = subs[1]
                val carrier2 = sub2.carrierName?.toString() ?: "Unknown"
                tvSim2Carrier.text = carrier2
                tvSim2Status.text  = carrierLabel(carrier2)
                btnCheckSim2.setOnClickListener {
                    fireUssd(ctx, carrier2, sub2.subscriptionId)
                }
                cardSim2.visibility = View.VISIBLE
                return
            }
        }

        // Single SIM fallback
        val carrier = telephony.networkOperatorName
            ?.takeIf { it.isNotBlank() } ?: "Unknown carrier"
        tvSim1Carrier.text = carrier
        tvSim1Status.text  = carrierLabel(carrier)
        btnCheckSim1.setOnClickListener { fireUssd(ctx, carrier, -1) }
    }

    private fun carrierLabel(carrier: String): String {
        val c = carrier.lowercase()
        return when {
            c.contains("globe") || c.contains("tm")                        -> "Globe / TM"
            c.contains("smart") || c.contains("tnt") || c.contains("sun") -> "Smart / TNT / Sun"
            c.contains("dito")                                             -> "DITO Telecommunity"
            else                                                           -> "$carrier"
        }
    }

    // ── USSD ─────────────────────────────────────────────────────────────────

    private fun ussdCode(carrier: String): String {
        val c = carrier.lowercase()
        return when {
            c.contains("smart") || c.contains("tnt") || c.contains("sun") -> "*123#"
            else -> "*143#"   // Globe, TM, DITO all use *143#
        }
    }

    private fun fireUssd(ctx: Context, carrier: String, subscriptionId: Int) {
        val code = ussdCode(carrier)

        progressBar.visibility   = View.VISIBLE
        tvResultLabel.text       = "Querying $carrier via $code…"
        tvResultLabel.visibility = View.VISIBLE
        hideStructuredResult()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            progressBar.visibility = View.GONE
            tvResultLabel.text =
                "Your Android version doesn't support automatic USSD.\n" +
                        "Dial $code manually to check your balance."
            return
        }

        val telephony: TelephonyManager =
            if (subscriptionId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                    .createForSubscriptionId(subscriptionId)
            } else {
                ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            }

        // Explicit permission check required by Android lint
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {

            telephony.sendUssdRequest(
                code,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        tm: TelephonyManager, request: String, response: CharSequence
                    ) {
                        Handler(Looper.getMainLooper()).post {
                            progressBar.visibility   = View.GONE
                            tvResultLabel.visibility = View.GONE
                            // Parse — NEVER store the raw response
                            val parsed = UssdParser.parse(carrier, response.toString())
                            showStructuredResult(parsed)
                        }
                    }

                    override fun onReceiveUssdResponseFailed(
                        tm: TelephonyManager, request: String, failureCode: Int
                    ) {
                        Handler(Looper.getMainLooper()).post {
                            progressBar.visibility = View.GONE
                            tvResultLabel.text =
                                "USSD request failed (code $failureCode).\n\n" +
                                        "This can happen if:\n" +
                                        "• You're not the default SMS app\n" +
                                        "• The network is temporarily unavailable\n\n" +
                                        "Try dialling $code manually."
                            tvResultLabel.setTextColor(CipherPalette.danger)
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

        } else {
            progressBar.visibility = View.GONE
            tvResultLabel.text =
                "Phone permission not granted. Tap Check Balance to request it."
            tvResultLabel.setTextColor(CipherPalette.warning)
        }
    }

    private fun showStructuredResult(r: UssdParser.UssdResult) {
        if (r.balance != null) {
            tvBalance.text       = r.balance
            tvBalance.visibility = View.VISIBLE
        }
        if (r.activePromo != null) {
            tvPromo.text       = "Active promo: ${r.activePromo}"
            tvPromo.visibility = View.VISIBLE
        }
        if (r.expiry != null) {
            tvExpiry.text       = "Expires: ${r.expiry}"
            tvExpiry.visibility = View.VISIBLE
        }
        if (r.dataBalance != null) {
            tvData.text       = "Data: ${r.dataBalance}"
            tvData.visibility = View.VISIBLE
        }
        // If nothing was parsed, show raw text as fallback
        if (r.balance == null && r.activePromo == null) {
            tvRawFallback.text       = r.rawText
            tvRawFallback.visibility = View.VISIBLE
        }
    }

    private fun hideStructuredResult() {
        listOf(tvBalance, tvPromo, tvExpiry, tvData, tvRawFallback)
            .forEach { it.visibility = View.GONE }
    }

    // ── History section ──────────────────────────────────────────────────────

    private fun loadHistorySection() {
        val ctx = context ?: return
        llHistory.removeAllViews()

        if (!VaultKeyManager.isUnlocked()) {
            tvHistoryEmpty.text       = "Unlock the vault to view SMS history."
            tvHistoryEmpty.visibility = View.VISIBLE
            btnClearHist.visibility   = View.GONE
            return
        }

        val history = LoadHistoryManager.loadHistory(ctx)
        if (history.isEmpty()) {
            tvHistoryEmpty.text       = "No SMS sent through CHIPHERLOCK yet."
            tvHistoryEmpty.visibility = View.VISIBLE
            btnClearHist.visibility   = View.GONE
            return
        }

        tvHistoryEmpty.visibility = View.GONE
        btnClearHist.visibility   = View.VISIBLE

        val sdf     = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val daySdf  = SimpleDateFormat("yyyyMMdd",     Locale.US)
        val timeSdf = SimpleDateFormat("h:mm a",       Locale.US)
        val grouped = history.groupBy { daySdf.format(Date(it.timestampMs)) }

        grouped.forEach { (_, entries) ->
            val dayHeader = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
            }
            val dateStr  = sdf.format(Date(entries.first().timestampMs))
            val dayTotal = entries.sumOf { it.costPesos }
            val dayCount = entries.size

            dayHeader.addView(label(ctx, dateStr, 12f, "#1976D2").also {
                it.typeface     = Typeface.DEFAULT_BOLD
                it.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            dayHeader.addView(label(ctx,
                "$dayCount SMS · ₱${"%.2f".format(dayTotal)}",
                12f, "#666666"))
            llHistory.addView(dayHeader)

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background  = roundedBg(ctx, CipherPalette.surface)
                setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(ctx, 4) }
            }

            entries.forEach { entry ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
                }
                val time      = timeSdf.format(Date(entry.timestampMs))
                val anonId    = "···${entry.recipientHash.takeLast(6)}"
                val costStr   = if (entry.costPesos == 0.0) "₱0.00"
                else "₱${"%.2f".format(entry.costPesos)}"
                val costColor = if (entry.costPesos > 0) "#CC0000" else "#00AA44"

                row.addView(label(ctx, time, 11f, "#888888").also {
                    it.layoutParams = LinearLayout.LayoutParams(
                        dp(ctx, 60), LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                row.addView(label(ctx, "→  $anonId", 12f, "#333333").also {
                    it.layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                row.addView(label(ctx, costStr, 12f, costColor))
                card.addView(row)

                card.addView(label(ctx, "    ${entry.promoName}", 10f, "#AAAAAA").also {
                    it.setPadding(dp(ctx, 60), 0, 0, dp(ctx, 2))
                })
            }
            llHistory.addView(card)
        }

        val totalSms  = history.size
        val totalCost = history.sumOf { it.costPesos }
        llHistory.addView(label(ctx,
            "All time: $totalSms SMS · ₱${"%.2f".format(totalCost)} spent",
            12f, "#666666").also { it.setPadding(0, dp(ctx, 12), 0, 0) })
    }

    private fun confirmClearHistory(ctx: Context) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Clear SMS History")
            .setMessage(
                "This will permanently delete all SMS spend history.\n\n" +
                        "The file will be securely overwritten (3-pass). " +
                        "This cannot be undone."
            )
            .setPositiveButton("Clear history") { _, _ ->
                LoadHistoryManager.clearHistory(ctx)
                loadHistorySection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun label(ctx: Context, text: String, size: Float, colorHex: String) =
        TextView(ctx).apply {
            this.text = text
            textSize  = size
            setTextColor(when (colorHex.uppercase()) {
                "#1976D2" -> CipherPalette.primary
                "#CC0000" -> CipherPalette.danger
                "#00AA44" -> CipherPalette.success
                "#1A1A1A", "#333333" -> CipherPalette.textPrimary
                "#444444", "#555555", "#666666" -> CipherPalette.textSecondary
                else -> CipherPalette.textMuted
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun roundedBg(ctx: Context, color: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, 8).toFloat()
        }

    private fun dp(ctx: Context, v: Int) =
        (v * ctx.resources.displayMetrics.density).toInt()
}