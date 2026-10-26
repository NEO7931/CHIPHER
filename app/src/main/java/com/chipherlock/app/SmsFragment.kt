package com.chipherlock.app

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.text.TextUtils
import android.app.AlertDialog
import android.telephony.SmsManager
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsFragment : Fragment() {

    private var _view: View? = null
    private var isBlacklistExpanded = false
    private var isViewingBlacklistLogs = false
    private lateinit var panelConversation: LinearLayout
    private lateinit var llBlacklistSection: LinearLayout

    // ── V7: Category Drawer state ─────────────────────────────────────
    private var isDrawerOpen = false
    private var currentCategory = SmsCategory.INBOX

    // ── V7: Drawer views ──────────────────────────────────────────────
    private lateinit var drawerHeader: LinearLayout
    private lateinit var tvDrawerActiveLabel: TextView
    private lateinit var tvDrawerActiveBadge: TextView
    private lateinit var ivDrawerChevron: TextView
    private lateinit var drawerContent: LinearLayout
    private lateinit var tvBellBadge: TextView
    private lateinit var drawerBadgeInbox: TextView
    private lateinit var drawerBadgeScams: TextView
    private lateinit var drawerBadgeQZone: TextView

    // ── V7: SCAMS panel views ─────────────────────────────────────────
    private lateinit var panelScams: LinearLayout
    private lateinit var llScamsList: LinearLayout
    private lateinit var tvScamsPlaceholder: TextView
    private lateinit var tvScamsCount: TextView

    // ── V7: BANKING panel views ───────────────────────────────────────
    private lateinit var panelBanking: LinearLayout
    private lateinit var llBankingList: LinearLayout
    private lateinit var tvBankingPlaceholder: TextView
    private lateinit var tvBankingCount: TextView
    private lateinit var drawerBadgeBanking: TextView

    // ── Tab buttons ──────────────────────────────────────────────────────
    private lateinit var btnTabInbox: Button
    private lateinit var btnTabScanner: Button
    private lateinit var btnTabQuarantine: Button
    private lateinit var btnTabHistory: Button

    // ── Panels ───────────────────────────────────────────────────────────
    private lateinit var panelInbox: LinearLayout
    private lateinit var panelScanner: LinearLayout
    private lateinit var panelQuarantine: LinearLayout
    private lateinit var panelHistory: LinearLayout

    // ── Inbox ────────────────────────────────────────────────────────────
    private lateinit var tvInboxCount: TextView
    private lateinit var tvInboxLegend: TextView
    private lateinit var llInboxList: LinearLayout

    // Inbox three-tier filter — populated in bindViews, click-handled at the
    // end of bindViews, and consumed by loadInbox to filter+style.
    private lateinit var tabFilterContacts: TextView
    private lateinit var tabFilterCompanies: TextView
    private lateinit var tabFilterUnknown: TextView

    // Current Inbox sub-filter. Memory-only — resets to CONTACT (safest) on
    // app restart. Drives loadInbox()'s row filter and the visual selected
    // state on the three filter tab buttons.
    private var inboxFilter: SenderTrust = SenderTrust.CONTACT

    // ── Scanner ──────────────────────────────────────────────────────────
    private lateinit var btnScanSms: Button
    private lateinit var switchAutoQuarantine: Switch
    private lateinit var tvAutoQuarantineStatus: TextView
    private lateinit var tvScanSummary: TextView
    private lateinit var tvSmsPlaceholder: TextView
    private lateinit var llScanResults: LinearLayout

    // ── AI status (Blueprint 2: Groq integration) ──────────────────────
    private lateinit var llAiStatus: LinearLayout
    private lateinit var aiLoadingBar: ProgressBar
    private lateinit var tvAiStatus: TextView

    // ── Q-Zone ───────────────────────────────────────────────────────────
    private lateinit var tvQuarantineCount: TextView
    private lateinit var btnDeleteAllQuarantine: Button
    private lateinit var llQuarantineList: LinearLayout
    private lateinit var tvQuarantinePlaceholder: TextView

    // ── History ──────────────────────────────────────────────────────────
    private lateinit var tvHistoryCount: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var llHistoryList: LinearLayout

    // Launcher for requesting default SMS app role
    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loadQZone() }

    // ── Real-time inbox refresh receiver ─────────────────────────────────
    private val inboxRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            val expectedSender = intent?.getStringExtra("sender")
            val handler = Handler(Looper.getMainLooper())

            fun tryLoad(attemptsLeft: Int, delayMs: Long) {
                handler.postDelayed({
                    if (_view == null) return@postDelayed
                    val messages = readSmsAllThreads(ctx)
                    val found = expectedSender == null ||
                            messages.any {
                                it.sender.trim().equals(expectedSender.trim(), ignoreCase = true)
                            }
                    if (found || attemptsLeft <= 0) {
                        loadInbox()
                    } else {
                        tryLoad(attemptsLeft - 1, 200)
                    }
                }, delayMs)
            }

            tryLoad(5, 0)
        }
    }

    // ── Real-time Q-Zone refresh receiver ────────────────────────────────
    private val quarantineUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (_view != null && panelQuarantine.visibility == View.VISIBLE) {
                loadQZone()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        _view = inflater.inflate(R.layout.fragment_sms, container, false)
        return _view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        RuleEngine.load(requireContext())
        bindViews(view)
        setupBlacklistSection()
        setupDrawer()
        setupTabs()
        setupScanner()

        val prefs = requireContext().getSharedPreferences("scam_alert_prefs", Context.MODE_PRIVATE)
        val pending = prefs.getString("pending_alert", null)
        if (pending != null) {
            prefs.edit().remove("pending_alert").apply()
            showTab("HISTORY")
        } else {
            showTab("INBOX")
        }

        autoScanOnOpen()
    }

    override fun onResume() {
        super.onResume()
        ContactsHelper.clearCache()
        NotificationCounter.recomputeFromSource(requireContext())
        updateDrawerHeader()
        SafeExportManager.showPreservationNoticeIfNeeded(
            requireContext(), isDefaultSmsApp(requireContext())
        )
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(
                inboxRefreshReceiver,
                IntentFilter("com.chipherlock.SMS_RECEIVED_REFRESH")
            )
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(
                quarantineUpdateReceiver,
                IntentFilter("com.chipherlock.QUARANTINE_UPDATED")
            )
        loadInbox()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(inboxRefreshReceiver)
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(quarantineUpdateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _view = null
    }

    private fun bindViews(v: View) {
        btnTabInbox = v.findViewById(R.id.btnTabInbox)
        btnTabScanner = v.findViewById(R.id.btnTabScanner)
        btnTabQuarantine = v.findViewById(R.id.btnTabQuarantine)
        btnTabHistory = v.findViewById(R.id.btnTabHistory)

        panelInbox = v.findViewById(R.id.panelInbox)
        panelScanner = v.findViewById(R.id.panelScanner)
        panelQuarantine = v.findViewById(R.id.panelQuarantine)
        panelHistory = v.findViewById(R.id.panelHistory)

        tvInboxCount = v.findViewById(R.id.tvInboxCount)
        tvInboxLegend = v.findViewById(R.id.tvInboxLegend)
        llInboxList = v.findViewById(R.id.llInboxList)
        tabFilterContacts = v.findViewById(R.id.tabFilterContacts)
        tabFilterCompanies = v.findViewById(R.id.tabFilterCompanies)
        tabFilterUnknown = v.findViewById(R.id.tabFilterUnknown)

        btnScanSms = v.findViewById(R.id.btnScanSms)
        switchAutoQuarantine = v.findViewById(R.id.switchAutoQuarantine)
        tvAutoQuarantineStatus = v.findViewById(R.id.tvAutoQuarantineStatus)
        tvScanSummary = v.findViewById(R.id.tvScanSummary)
        tvSmsPlaceholder = v.findViewById(R.id.tvSmsPlaceholder)
        llScanResults = v.findViewById(R.id.llScanResults)

        // AI status row (added in Blueprint 2)
        llAiStatus = v.findViewById(R.id.llAiStatus)
        aiLoadingBar = v.findViewById(R.id.aiLoadingBar)
        tvAiStatus = v.findViewById(R.id.tvAiStatus)

        tvQuarantineCount = v.findViewById(R.id.tvQuarantineCount)
        btnDeleteAllQuarantine = v.findViewById(R.id.btnDeleteAllQuarantine)
        llQuarantineList = v.findViewById(R.id.llQuarantineList)
        tvQuarantinePlaceholder = v.findViewById(R.id.tvQuarantinePlaceholder)

        tvHistoryCount = v.findViewById(R.id.tvHistoryCount)
        btnClearHistory = v.findViewById(R.id.btnClearHistory)
        llHistoryList = v.findViewById(R.id.llHistoryList)
        panelConversation = v.findViewById(R.id.panelConversation)

        // V7: Drawer views
        drawerHeader        = v.findViewById(R.id.drawerHeader)
        tvDrawerActiveLabel = v.findViewById(R.id.tvDrawerActiveLabel)
        tvDrawerActiveBadge = v.findViewById(R.id.tvDrawerActiveBadge)
        ivDrawerChevron     = v.findViewById(R.id.ivDrawerChevron)
        drawerContent       = v.findViewById(R.id.drawerContent)
        tvBellBadge         = v.findViewById(R.id.tvBellBadge)
        drawerBadgeInbox    = v.findViewById(R.id.drawerBadgeInbox)
        drawerBadgeScams    = v.findViewById(R.id.drawerBadgeScams)
        drawerBadgeQZone    = v.findViewById(R.id.drawerBadgeQZone)

        // V7: SCAMS panel views
        panelScams          = v.findViewById(R.id.panelScams)
        llScamsList         = v.findViewById(R.id.llScamsList)
        tvScamsPlaceholder  = v.findViewById(R.id.tvScamsPlaceholder)
        tvScamsCount        = v.findViewById(R.id.tvScamsCount)

        // V7: BANKING panel views
        panelBanking        = v.findViewById(R.id.panelBanking)
        llBankingList       = v.findViewById(R.id.llBankingList)
        tvBankingPlaceholder= v.findViewById(R.id.tvBankingPlaceholder)
        tvBankingCount      = v.findViewById(R.id.tvBankingCount)
        drawerBadgeBanking  = v.findViewById(R.id.drawerBadgeBanking)

        // Inbox tier-filter tabs — single-select; clicking re-renders
        tabFilterContacts.setOnClickListener {
            inboxFilter = SenderTrust.CONTACT
            loadInbox()
        }
        tabFilterCompanies.setOnClickListener {
            inboxFilter = SenderTrust.VERIFIED
            loadInbox()
        }
        tabFilterUnknown.setOnClickListener {
            inboxFilter = SenderTrust.UNKNOWN
            loadInbox()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // V7: BANKING PANEL
    // ════════════════════════════════════════════════════════════════════

    private fun loadBanking() {
        val ctx = context ?: return
        val dp  = ctx.resources.displayMetrics.density
        llBankingList.removeAllViews()

        val bankingMessages = readSmsAllThreads(ctx).filter { msg ->
            val s = msg.sender.trim()
            val isAlphanumeric = s.isNotEmpty() &&
                    !s.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }
            VerifiedSenders.isVerified(s) && isAlphanumeric
        }

        tvBankingCount.text = "Banking — ${bankingMessages.size} messages"

        if (bankingMessages.isEmpty()) {
            tvBankingPlaceholder.visibility = View.VISIBLE
            return
        }
        tvBankingPlaceholder.visibility = View.GONE

        val grouped = bankingMessages.groupBy { it.sender.trim().uppercase() }

        for ((senderKey, msgs) in grouped.entries.sortedBy { it.key }) {
            val latest = msgs.first()
            val card = androidx.cardview.widget.CardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                radius = 8 * dp
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            }
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(ctx).apply {
                text = senderKey; textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            headerRow.addView(TextView(ctx).apply {
                text = "  Verified  "; textSize = 11f
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().also {
                    it.setColor(CipherPalette.info); it.cornerRadius = 12 * dp
                }
            })
            row.addView(headerRow)
            row.addView(TextView(ctx).apply {
                text = "${msgs.size} message${if (msgs.size != 1) "s" else ""}"
                textSize = 12f; setTextColor(CipherPalette.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (2*dp).toInt() }
            })
            row.addView(TextView(ctx).apply {
                text = latest.body.take(80); textSize = 13f
                setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (4*dp).toInt() }
            })
            card.addView(row)
            llBankingList.addView(card)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // TABS
    // ════════════════════════════════════════════════════════════════════

    private fun setupTabs() {
        btnTabInbox.setOnClickListener { showTab("INBOX") }
        btnTabScanner.setOnClickListener { showTab("SCANNER") }
        btnTabQuarantine.setOnClickListener { showTab("QUARANTINE") }
        btnTabHistory.setOnClickListener { showTab("HISTORY") }
    }

    private fun showTab(tab: String) {
        val ctx = context ?: return
        panelInbox.visibility      = if (tab == "INBOX")      View.VISIBLE else View.GONE
        panelScams.visibility = if (tab == "SCAMS") View.VISIBLE else View.GONE
        panelBanking.visibility    = if (tab == "BANKING")    View.VISIBLE else View.GONE
        panelScanner.visibility = if (tab == "SCANNER") View.VISIBLE else View.GONE
        panelQuarantine.visibility = if (tab == "QUARANTINE") View.VISIBLE else View.GONE
        panelHistory.visibility = if (tab == "HISTORY") View.VISIBLE else View.GONE
        panelConversation.visibility = View.GONE

        currentCategory = when (tab) {
            "INBOX"      -> SmsCategory.INBOX
            "SCAMS"      -> SmsCategory.SCAMS
            "BANKING"    -> SmsCategory.BANKING
            "SCANNER"    -> SmsCategory.SCANNER
            "QUARANTINE" -> SmsCategory.QZONE
            "HISTORY"    -> SmsCategory.HISTORY
            else         -> SmsCategory.INBOX
        }
        updateDrawerHeader()

        val on = Color.WHITE
        val off = CipherPalette.textDisabled
        btnTabInbox.setTextColor(if (tab == "INBOX") on else off)
        btnTabScanner.setTextColor(if (tab == "SCANNER") on else off)
        btnTabQuarantine.setTextColor(if (tab == "QUARANTINE") on else off)
        btnTabHistory.setTextColor(if (tab == "HISTORY") on else off)

        when (tab) {
            "INBOX"      -> {
                NotificationCounter.markRead(ctx, SmsCategory.INBOX)
                loadInbox()
                updateDrawerHeader()
            }
            "SCAMS"      -> loadScams()
            "BANKING"    -> loadBanking()
            "SCANNER"    -> updateQuarantineToggleUI()
            "QUARANTINE" -> loadQZone()
            "HISTORY"    -> loadHistoryTab()
        }
    }
    // ════════════════════════════════════════════════════════════════════
    // V7: CATEGORY DRAWER
    // ════════════════════════════════════════════════════════════════════

    private fun setupDrawer() {
        val ctx = context ?: return
        drawerHeader.setOnClickListener { toggleDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowInbox)
            ?.setOnClickListener { showTab("INBOX");      closeDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowScams)
            ?.setOnClickListener { showTab("SCAMS");      closeDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowScanner)
            ?.setOnClickListener { showTab("SCANNER");    closeDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowQZone)
            ?.setOnClickListener { showTab("QUARANTINE"); closeDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowBanking)
            ?.setOnClickListener { showTab("BANKING");    closeDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowHistory)
            ?.setOnClickListener { showTab("HISTORY");    closeDrawer() }
        drawerContent.findViewById<LinearLayout>(R.id.drawerRowSafeExport)
            ?.setOnClickListener {
                val c = context ?: return@setOnClickListener
                closeDrawer()
                SafeExportManager.showSafeExportDialog(c, isDefaultSmsApp(c))
            }
        view?.findViewById<android.widget.ImageButton>(R.id.btnBell)
            ?.setOnClickListener {
                NotificationCounter.markAllRead(ctx)
                updateDrawerHeader()
            }
        updateDrawerHeader()
    }

    private fun toggleDrawer() {
        isDrawerOpen = !isDrawerOpen
        drawerContent.visibility = if (isDrawerOpen) View.VISIBLE else View.GONE
        ivDrawerChevron.text = if (isDrawerOpen) "▲" else "▼"
    }

    private fun closeDrawer() {
        if (isDrawerOpen) {
            isDrawerOpen = false
            drawerContent.visibility = View.GONE
            ivDrawerChevron.text = "▼"
        }
    }

    fun updateDrawerHeader() {
        val ctx = context ?: return
        tvDrawerActiveLabel.text = when (currentCategory) {
            SmsCategory.INBOX   -> "Inbox"
            SmsCategory.SCAMS   -> "Scams"
            SmsCategory.BANKING -> "Banking"
            SmsCategory.QZONE   -> "Q-Zone"
            SmsCategory.HISTORY -> "History"
            SmsCategory.SCANNER -> "Scanner"
        }

        val activeCount = NotificationCounter.getCount(ctx, currentCategory)
        if (activeCount > 0) {
            tvDrawerActiveBadge.visibility = View.VISIBLE
            tvDrawerActiveBadge.text = if (activeCount > 99) "99+" else activeCount.toString()
        } else {
            tvDrawerActiveBadge.visibility = View.GONE
        }

        fun syncBadge(tv: TextView, cat: SmsCategory) {
            val c = NotificationCounter.getCount(ctx, cat)
            if (c > 0) { tv.visibility = View.VISIBLE; tv.text = if (c > 99) "99+" else c.toString() }
            else tv.visibility = View.GONE
        }
        syncBadge(drawerBadgeInbox, SmsCategory.INBOX)
        syncBadge(drawerBadgeScams, SmsCategory.SCAMS)
        syncBadge(drawerBadgeQZone, SmsCategory.QZONE)

        val bellTotal = NotificationCounter.getBellTotal(ctx)
        if (bellTotal > 0) {
            tvBellBadge.visibility = View.VISIBLE
            tvBellBadge.text = if (bellTotal > 99) "99+" else bellTotal.toString()
        } else {
            tvBellBadge.visibility = View.GONE
        }
    }
    // ════════════════════════════════════════════════════════════════════
    // BLACKLIST SECTION
    // ════════════════════════════════════════════════════════════════════

    private fun setupBlacklistSection() {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        llBlacklistSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(8, dp) }
        }
        panelInbox.addView(llBlacklistSection, 0)
        loadBlacklistSection()
    }

    private fun loadBlacklistSection() {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        val red = CipherPalette.danger
        val dark = CipherPalette.danger
        llBlacklistSection.removeAllViews()

        val list = loadBlacklist(ctx)

        // ── Header (always visible) ──
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CipherPalette.dangerContainer)
            val p = m(10, dp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(TextView(ctx).apply {
            text = "ACCESS LIST  —  ${list.size} blocked"
            textSize = 13f
            setTypeface(null, Typeface.BOLD); setTextColor(dark)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(ctx).apply {
            text = if (isViewingBlacklistLogs) "← INBOX" else "LOGS"
            textSize = 11f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(CipherPalette.warning)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, m(36, dp)
            ).also { it.marginEnd = m(6, dp) }
            setOnClickListener {
                isViewingBlacklistLogs = !isViewingBlacklistLogs
                if (!isViewingBlacklistLogs) isBlacklistExpanded = false
                loadBlacklistSection()
                loadInbox()
            }
        })
        header.addView(Button(ctx).apply {
            text = if (isBlacklistExpanded) "HIDE ▲" else "MANAGE ▼"
            textSize = 11f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(red)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, m(36, dp)
            )
            setOnClickListener {
                isBlacklistExpanded = !isBlacklistExpanded
                loadBlacklistSection()
            }
        })
        llBlacklistSection.addView(header)

        if (!isBlacklistExpanded) return

        // ── Body (visible when expanded) ──
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CipherPalette.dangerContainer)
            val p = m(10, dp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val etInput = EditText(ctx).apply {
            hint = "Enter number or sender name"
            textSize = 13f; setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = m(8, dp) }
        }
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(10, dp) }
        }
        inputRow.addView(etInput)
        inputRow.addView(Button(ctx).apply {
            text = "+ BLOCK"; textSize = 11f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(red)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val c = context ?: return@setOnClickListener
                val input = etInput.text.toString().trim()
                if (input.isEmpty()) {
                    Toast.makeText(c, "Enter a sender name or number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveToBlacklist(
                    c, BlacklistEntry(
                        sender = input,
                        reason = "Manual",
                        timestamp = System.currentTimeMillis()
                    )
                )
                etInput.text.clear()
                Toast.makeText(c, "\"$input\" is now blocked", Toast.LENGTH_SHORT).show()
                loadBlacklistSection()
                loadInbox()
            }
        })
        body.addView(inputRow)

        if (list.isEmpty()) {
            body.addView(TextView(ctx).apply {
                text = "No senders blocked yet."
                textSize = 12f; setTextColor(CipherPalette.textMuted)
            })
        } else {
            list.forEach { entry ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = m(6, dp) }
                }
                row.addView(TextView(ctx).apply {
                    text = "${entry.sender}"
                    textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(dark)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = entry.reason; textSize = 11f; setTextColor(CipherPalette.textMuted)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = m(8, dp) }
                })
                row.addView(Button(ctx).apply {
                    text = "✕"; textSize = 11f; setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(CipherPalette.surfaceHigh)
                    layoutParams = LinearLayout.LayoutParams(m(40, dp), m(36, dp))
                    setOnClickListener {
                        val c = context ?: return@setOnClickListener
                        removeFromBlacklist(c, entry.sender)
                        Toast.makeText(c, "\"${entry.sender}\" unblocked", Toast.LENGTH_SHORT)
                            .show()
                        loadBlacklistSection()
                        loadInbox()
                    }
                })
                body.addView(row)
            }
        }
        llBlacklistSection.addView(body)
    }

    // ════════════════════════════════════════════════════════════════════
    // V7: SCAMS PANEL
    // ════════════════════════════════════════════════════════════════════

    private fun loadScams() {
        val ctx = context ?: return
        val dp  = ctx.resources.displayMetrics.density
        llScamsList.removeAllViews()

        val arr = try {
            org.json.JSONArray(
                ctx.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                    .getString("scam_history_json", "[]") ?: "[]"
            )
        } catch (e: Exception) {
            tvScamsCount.text = "Scams — 0 flagged"
            tvScamsPlaceholder.visibility = View.VISIBLE
            return
        }

        val scams = mutableListOf<ScamHistoryEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lvl = obj.optString("riskLevel", "LOW")
            if (lvl != "MEDIUM" && lvl != "HIGH") continue
            val entry = ScamHistoryEntry.fromJson(obj)
            // Skip messages from known contacts — they belong in Inbox
            if (ContactsHelper.isContact(ctx, entry.sender)) continue
            scams.add(entry)
        }

        tvScamsCount.text = "Scams — ${scams.size} flagged"

        if (scams.isEmpty()) {
            tvScamsPlaceholder.visibility = View.VISIBLE
            NotificationCounter.markRead(ctx, SmsCategory.SCAMS)
            updateDrawerHeader()
            return
        }
        tvScamsPlaceholder.visibility = View.GONE

        for (entry in scams) {
            val badgeColor = if (entry.riskLevel == "HIGH") "#D32F2F" else "#E65100"
            val card = androidx.cardview.widget.CardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                radius = 8 * dp
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            }
            val senderRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            senderRow.addView(TextView(ctx).apply {
                text = entry.sender; textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            senderRow.addView(TextView(ctx).apply {
                text = "  ${entry.riskLevel}  "; textSize = 11f
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().also {
                    it.setColor(Color.parseColor(badgeColor))
                    it.cornerRadius = 12 * dp
                }
            })
            row.addView(senderRow)
            row.addView(TextView(ctx).apply {
                text = entry.messagePreview; textSize = 13f
                setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (4*dp).toInt() }
            })
            card.addView(row)
            llScamsList.addView(card)
        }

        NotificationCounter.markRead(ctx, SmsCategory.SCAMS)
        updateDrawerHeader()
    }

    // ════════════════════════════════════════════════════════════════════
    // BULK QUARANTINE — called when Auto-Quarantine is toggled ON
    // ════════════════════════════════════════════════════════════════════

    private fun bulkQuarantineHighRisk() {
        val ctx = context ?: return

        val quarantinedIds = loadQuarantine(ctx)
            .map { it.smsId }
            .filter { it != -1L }
            .toSet()

        val blacklistedSenders = loadBlacklist(ctx)
            .map { it.sender.trim().lowercase() }.toSet()

        // Phase 1 — keyword analyze everything (cheap, synchronous)
        val baseline = getOrSetBaseline(ctx)
        val candidates: List<Pair<SmsEntry, ScoreBreakdown>> = readSmsInbox(ctx, 100)
            .asSequence()
            .filter { it.smsId > baseline }                                    // never touch pre-existing messages
            .filter { it.sender.trim().lowercase() !in blacklistedSenders }
            .filter { it.smsId !in quarantinedIds }
            .map { msg -> msg to analyzeMessage(msg.sender, msg.body) }
            .toList()

        // Phase 2 — pick which ones deserve AI verification.
        // Verify everything (capped at 15 by recency) instead of pre-filtering
        // by keyword score, so socially-engineered scams that have no scam
        // words and no URL still get a real AI check before we decide whether
        // to auto-quarantine them.
        val aiCandidates = candidates.take(15)

        // No AI candidates or no key — fall back to keyword-only quarantine
        if (aiCandidates.isEmpty() || !GroqScamAnalyzer.isConfigured()) {
            applyBulkQuarantine(ctx, candidates)
            return
        }

        // Phase 3 — parallel AI verification, then quarantine on fused score
        Toast.makeText(
            ctx,
            "Groq verifying ${aiCandidates.size} message(s) before quarantine…",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            val aiByKey: Map<String, GroqScamAnalyzer.ScamResult> = withContext(Dispatchers.IO) {
                aiCandidates.map { (msg, _) ->
                    async {
                        val key = msg.smsId.toString() + "|" + msg.body.take(60)
                        val isContact = ContactsHelper.isContact(ctx, msg.sender)
                        key to GroqScamAnalyzer.analyze(msg.body, isContact = isContact)
                    }
                }.awaitAll().toMap()
            }
            if (_view == null) return@launch  // fragment torn down mid-call

            val fused: List<Pair<SmsEntry, ScoreBreakdown>> = candidates.map { (msg, kwBd) ->
                val key = msg.smsId.toString() + "|" + msg.body.take(60)
                val ai = aiByKey[key]
                // Pass through fusion even when ai.success is false so the
                // "AI unavailable" factor shows up in History's breakdown.
                msg to if (ai != null) fuseBreakdowns(kwBd, ai, msg.sender) else kwBd
            }
            applyBulkQuarantine(ctx, fused)
        }
    }

    // Shared tail for bulkQuarantineHighRisk — counts HIGH-fused messages,
    // moves each to Q-Zone, and notifies the user with a single toast.
    private fun applyBulkQuarantine(
        ctx: Context,
        results: List<Pair<SmsEntry, ScoreBreakdown>>
    ) {
        var added = 0
        for ((msg, bd) in results) {
            if (bd.level == "HIGH") {
                // Never blacklist a verified-service sender ID. The scam
                // is a spoof; the real brand uses that same ID legitimately.
                saveToQuarantine(
                    ctx,
                    msg.toQuarantineEntry(bd),
                    autoBlacklist = isAutoQuarantineEnabled(ctx) &&
                            !VerifiedSenders.isVerified(msg.sender)
                )
                added++
            }
        }

        if (added > 0) {
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent("com.chipherlock.QUARANTINE_UPDATED"))
            Toast.makeText(
                ctx,
                "$added HIGH risk message(s) moved to Q-Zone",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(ctx, "No HIGH risk messages found", Toast.LENGTH_SHORT).show()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // INBOX TAB — Messenger-style conversation threads
    // ════════════════════════════════════════════════════════════════════

    private fun loadInbox() {
        val ctx = context ?: return
        if (isViewingBlacklistLogs) {
            loadBlacklistLogs(); return
        }
        llInboxList.removeAllViews()

        val quarantine = loadQuarantine(ctx)
        val quarantinedIds = quarantine.map { it.smsId }.toSet()
        val quarantinedKeys = quarantine
            .map { it.sender.trim() + "|" + it.messageBody.take(60) }.toSet()
        val blacklistedSenders = loadBlacklist(ctx)
            .map { it.sender.trim().lowercase() }.toSet()

        val threads = readSmsAllThreads(ctx)

        // Pre-classify every non-hidden message exactly once so we can count
        // per-tier badges AND filter the visible list from a single pass.
        data class Classified(
            val msg: SmsEntry,
            val tier: SenderTrust,
            val result: AnalysisResult
        )

        val visible: List<Classified> = threads.mapNotNull { msg ->
            if (msg.sender.trim().lowercase() in blacklistedSenders) return@mapNotNull null
            val key = msg.sender.trim() + "|" + msg.body.take(60)
            if (msg.smsId in quarantinedIds || key in quarantinedKeys) return@mapNotNull null

            // Per-message risk lookup order, most authoritative first:
            //   1. Risk index (smsId-keyed, set after every scan/receive — accurate)
            //   2. History (sender+body-keyed — works for MEDIUM/HIGH only)
            //   3. Fresh keyword analyze (last resort; can over-flag legit
            //      bank/wallet messages that have no AI verdict yet)
            // Without step 1, a legit BPI alert with no History match falls
            // through to step 3 and gets scored MEDIUM on keyword "BPI" /
            // "account" alone — landing it incorrectly in the Unknown tab.
            val result = when {
                msg.type == 2 -> AnalysisResult(0, "LOW", "Suspicious")
                else -> {
                    val indexedLevel = getMessageRisk(ctx, msg.smsId)
                    if (indexedLevel != null) {
                        // We have an authoritative verdict — synthesize a
                        // result that uses it. Score and category are
                        // approximations; only level matters for tier routing.
                        val score = when (indexedLevel) {
                            "HIGH"   -> 85
                            "MEDIUM" -> 55
                            else     -> 0
                        }
                        AnalysisResult(score, indexedLevel, "Scanned")
                    } else {
                        historyVerdictFor(ctx, msg)
                            ?: analyzeMessage(msg.sender, msg.body).toAnalysisResult()
                    }
                }
            }
            val tier = classifySender(ctx, msg.sender, result.level)

            // Flagged messages from non-contacts are hidden from the inbox
            // entirely — they belong in the Scams tab or Q-Zone, not here.
            // Contacts are exempt because a HIGH score on a known contact
            // is almost always a false positive and hiding it would be
            // more confusing than showing it with a warning badge.
            if ((result.level == "HIGH" || result.level == "MEDIUM") &&
                tier != SenderTrust.CONTACT) return@mapNotNull null

            Classified(msg, tier, result)
        }

        // ── DEBUG: dump inbox state so we can see what's going on ─────────
        if (com.chipherlock.app.BuildConfig.DEBUG) {
            android.util.Log.d("CHIPHERLOCK_DBG", "═══ loadInbox dump ═══")
            android.util.Log.d("CHIPHERLOCK_DBG", "blacklistedSenders = $blacklistedSenders")
            android.util.Log.d("CHIPHERLOCK_DBG", "quarantinedIds = $quarantinedIds")
            android.util.Log.d("CHIPHERLOCK_DBG", "quarantinedKeys = $quarantinedKeys")
            android.util.Log.d("CHIPHERLOCK_DBG", "threads from readSmsAllThreads = ${threads.size}")
            threads.forEach { t ->
                val idxLvl = getMessageRisk(ctx, t.smsId) ?: "(null)"
                android.util.Log.d("CHIPHERLOCK_DBG",
                    "  [thread] sender='${t.sender}' smsId=${t.smsId} indexLvl=$idxLvl body='${t.body.take(40)}'")
            }
            android.util.Log.d("CHIPHERLOCK_DBG", "visible after filter = ${visible.size}")
            visible.forEach { v ->
                android.util.Log.d("CHIPHERLOCK_DBG",
                    "  [visible] sender='${v.msg.sender}' tier=${v.tier} level=${v.result.level} smsId=${v.msg.smsId}")
            }
            android.util.Log.d("CHIPHERLOCK_DBG", "═════════════════════════")
        } // end BuildConfig.DEBUG

        // Per-tier totals plus a risk-only count for the Unknown badge.
        val contactsAll  = visible.count { it.tier == SenderTrust.CONTACT  }
        val companiesAll = visible.count { it.tier == SenderTrust.VERIFIED }
        // Spoofed messages roll into the Unknown tab so users don't see
        // impersonators sitting under "Companies" with a trust badge.
        val unknownAll   = visible.count {
            it.tier == SenderTrust.UNKNOWN || it.tier == SenderTrust.SPOOFED
        }
        val unknownRisky = visible.count {
            (it.tier == SenderTrust.UNKNOWN || it.tier == SenderTrust.SPOOFED) &&
                    it.result.level != "LOW"
        }

        tabFilterContacts.text = "Contacts ($contactsAll)"
        tabFilterCompanies.text = "Companies ($companiesAll)"
        // Unknown shows risky-count if any, otherwise the plain total
        tabFilterUnknown.text =
            if (unknownRisky > 0) "Unknown ($unknownRisky)"
            else "Unknown ($unknownAll)"

        // Visual selected-state: full color for the active tab, dim gray
        // for the inactive ones.
        fun applyTabStyle(view: TextView, baseColor: String, isSelected: Boolean) {
            view.setBackgroundColor(
                Color.parseColor(if (isSelected) baseColor else "#BDBDBD")
            )
            view.alpha = if (isSelected) 1.0f else 0.85f
        }
        applyTabStyle(tabFilterContacts, "#2E7D32", inboxFilter == SenderTrust.CONTACT)
        applyTabStyle(tabFilterCompanies, "#FFC107", inboxFilter == SenderTrust.VERIFIED)
        applyTabStyle(tabFilterUnknown, "#D32F2F", inboxFilter == SenderTrust.UNKNOWN)

        // Top-of-list summary + legend
        val totalRisky = visible.count { it.result.level != "LOW" }
        tvInboxCount.text =
            "${visible.size} conversation${if (visible.size != 1) "s" else ""}" +
                    if (totalRisky > 0) " • $totalRisky suspicious" else ""
        tvInboxLegend.text = "Safe   Check   Scam   Quarantined"

        // Render only the active filter's messages
        // Unknown tab also includes SPOOFED so impersonators surface here,
        // not in the Companies tab where users would over-trust them.
        val filtered = visible.filter {
            when (inboxFilter) {
                SenderTrust.UNKNOWN -> it.tier == SenderTrust.UNKNOWN ||
                        it.tier == SenderTrust.SPOOFED
                else                -> it.tier == inboxFilter
            }
        }
        if (filtered.isEmpty()) {
            val emptyMsg = when (inboxFilter) {
                SenderTrust.CONTACT  -> "No messages from your contacts yet"
                SenderTrust.VERIFIED -> "No messages from verified services yet"
                SenderTrust.UNKNOWN  -> "No messages from unknown senders"
                SenderTrust.SPOOFED  -> "No messages from unknown senders"
            }
            llInboxList.addView(emptyState(ctx, emptyMsg))
            return
        }
        filtered.forEach { (msg, _, result) ->
            llInboxList.addView(buildConversationCard(ctx, msg, result))
        }

    }

    // Three-tier trust classification used by the Inbox tab.
    enum class SenderTrust {
        CONTACT,    // sender's number is in the device's Contacts app
        VERIFIED,   // sender matches the curated VerifiedSenders list AND
        // the message is benign (LOW). A "verified" sender ID
        // does not imply the content is safe — it's only a
        // trust hint, not proof of identity.
        SPOOFED,    // sender claims a VerifiedSenders identity BUT the
        // message is MEDIUM/HIGH risk. Treated as UNKNOWN for
        // filtering, but tagged with its own visual so the user
        // can see "this claims to be GCASH but it's lying."
        UNKNOWN     // everyone else
    }

    // Classify the trust tier of a sender. The optional [riskLevel] parameter
    // lets us downgrade a claimed-verified sender to SPOOFED when the message
    // content itself looks like a scam — a real BPI/GCASH never sends HIGH-risk
    // content, so a "VERIFIED + HIGH" combination is the signature of an
    // impersonator abusing the alphanumeric sender ID system.
    //
    // When [riskLevel] is null (e.g. when classifying purely by sender for
    // counts or filters before we have a verdict), VERIFIED is returned as
    // before — caller is responsible for re-classifying once risk is known.
    private fun classifySender(
        ctx: Context,
        sender: String,
        riskLevel: String? = null
    ): SenderTrust = when {
        ContactsHelper.isContact(ctx, sender) -> SenderTrust.CONTACT
        VerifiedSenders.isVerified(sender)    -> {
            // A "verified" sender carrying MEDIUM/HIGH risk content is
            // almost certainly being impersonated. Demote to SPOOFED so
            // the inbox filter treats it like Unknown.
            if (riskLevel == "HIGH" || riskLevel == "MEDIUM") SenderTrust.SPOOFED
            else SenderTrust.VERIFIED
        }
        else -> SenderTrust.UNKNOWN
    }

    // Reads the most-recent History entry matching this inbox message and
    // returns its risk verdict (level + score + category). Returns null if
    // we've never seen this message before — caller should fall back to a
    // fresh keyword scan in that case.
    //
    // Dedup key matches the rest of the codebase: sender + body.take(60).
    private fun historyVerdictFor(ctx: Context, msg: SmsEntry): AnalysisResult? {
        val key = msg.sender.trim() + "|" + msg.body.take(60)
        val match = loadHistory(ctx).firstOrNull { entry ->
            (entry.sender.trim() + "|" + entry.messagePreview.take(60)) == key
        } ?: return null
        return AnalysisResult(
            score = match.riskScore,
            level = match.riskLevel,
            category = match.category
        )
    }

    // ── Messenger-style conversation card ───────────────────────────────
    private fun buildConversationCard(ctx: Context, msg: SmsEntry, result: AnalysisResult): View {
        val dp = ctx.resources.displayMetrics.density
        // Risk color (red/orange) ALWAYS wins for HIGH and MEDIUM messages —
        // a scam from a hijacked contact is still a scam. Trust tier only
        // colors the avatar when the message itself is benign (LOW).
        val trustTier = classifySender(ctx, msg.sender, result.level)
        val riskColor = when (result.level) {
            "HIGH" -> CipherPalette.danger    // red
            "MEDIUM" -> CipherPalette.warning    // orange
            else -> when (trustTier) {
                SenderTrust.CONTACT  -> CipherPalette.success   // green for contacts
                SenderTrust.VERIFIED -> CipherPalette.primary   // blue for verified
                SenderTrust.SPOOFED  -> CipherPalette.danger   // red — impersonator
                SenderTrust.UNKNOWN  -> CipherPalette.textMuted   // gray for strangers
            }
        }
        val avatarColor = riskColor

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(CipherPalette.surface)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val px = m(14, dp);
            val py = m(10, dp)
            setPadding(px, py, px, py)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Pass risk to classifier so verified-sender spoofs are flagged
        // as SPOOFED (not VERIFIED) — visually they get a warning icon.
        val trust = classifySender(ctx, msg.sender, result.level)
        val avatarLetter = when {
            msg.type == 2                  -> "→"
            trust == SenderTrust.VERIFIED  -> "✓"
            trust == SenderTrust.SPOOFED   -> "!"
            else -> msg.sender.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }
        val avatarSize = m(48, dp)
        row.addView(TextView(ctx).apply {
            text = avatarLetter
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                .also { it.marginEnd = m(12, dp) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColor)
            }
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        nameRow.addView(TextView(ctx).apply {
            text = msg.sender
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(CipherPalette.textPrimary)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val nowCal = Calendar.getInstance()
        val msgCal = Calendar.getInstance().also { it.timeInMillis = msg.date }
        val timeStr = when {
            nowCal.get(Calendar.DATE) == msgCal.get(Calendar.DATE) &&
                    nowCal.get(Calendar.MONTH) == msgCal.get(Calendar.MONTH) ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.date))

            nowCal.get(Calendar.WEEK_OF_YEAR) == msgCal.get(Calendar.WEEK_OF_YEAR) &&
                    nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) ->
                SimpleDateFormat("EEE", Locale.getDefault()).format(Date(msg.date))

            else ->
                SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(msg.date))
        }
        nameRow.addView(TextView(ctx).apply {
            text = timeStr
            textSize = 11f
            setTextColor(CipherPalette.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = m(8, dp) }
        })
        col.addView(nameRow)

        val preview = if (msg.type == 2) "You: ${msg.body}" else msg.body
        col.addView(TextView(ctx).apply {
            text = preview
            textSize = 13f
            setTextColor(CipherPalette.textMuted)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = m(2, dp) }
        })

        if (msg.type != 2 && result.level != "LOW") {
            col.addView(TextView(ctx).apply {
                text = if (result.level == "HIGH") "HIGH · ${result.category}"
                else "SUSPICIOUS · ${result.category}"
                textSize = 11f
                setTextColor(riskColor)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(2, dp) }
            })
        }

        row.addView(col)
        wrapper.addView(row)

        wrapper.addView(View(ctx).apply {
            setBackgroundColor(CipherPalette.surfaceHigh)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // Pass the card's tier so the conversation view filters to messages
        // matching this trust level — legit GCash thread won't show spoofed
        // GCash phishing, and vice versa.
        wrapper.setOnClickListener { openConversation(msg.sender, trustTier) }
        wrapper.setOnLongClickListener { showMessageOptionsDialog(ctx, msg, result); true }

        return wrapper

    }

    // ════════════════════════════════════════════════════════════════════
    // BLACKLIST LOGS
    // ════════════════════════════════════════════════════════════════════

    private fun loadBlacklistLogs() {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        llInboxList.removeAllViews()

        val blacklistedSenders = loadBlacklist(ctx)
            .map { it.sender.trim().lowercase() }.toSet()

        if (blacklistedSenders.isEmpty()) {
            tvInboxCount.text = "No senders blacklisted yet"
            tvInboxLegend.text = "Add senders via MANAGE to see their messages here"
            llInboxList.addView(
                emptyState(
                    ctx,
                    "Your blacklist is empty.\nMessages from blocked senders will appear here."
                )
            )
            return
        }

        val allMessages = readSmsInbox(ctx, 200)
        val blocked = allMessages.filter {
            it.sender.trim().lowercase() in blacklistedSenders
        }

        val uniqueSenders = blocked.map { it.sender.trim() }.toSet().size
        tvInboxCount.text = "${blocked.size} blocked message(s) from $uniqueSenders sender(s)"
        tvInboxLegend.text = "Showing all messages from blacklisted senders"

        llInboxList.addView(Button(ctx).apply {
            text = "CLEAR ALL LOGS"; textSize = 11f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(CipherPalette.danger)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(10, dp) }
            setOnClickListener {
                val c = context ?: return@setOnClickListener
                val blSenders = loadBlacklist(c)
                    .map { it.sender.trim().lowercase() }.toSet()
                var deletedCount = 0
                try {
                    c.contentResolver.query(
                        Uri.parse("content://sms"),
                        arrayOf("_id", "address"),
                        null, null, null
                    )?.use { cursor ->
                        val cId = cursor.getColumnIndex("_id")
                        val cAddr = cursor.getColumnIndex("address")
                        while (cursor.moveToNext()) {
                            val addr = cursor.getString(cAddr) ?: continue
                            if (addr.trim().lowercase() in blSenders) {
                                val id = cursor.getLong(cId)
                                if (deleteFromPhone(c, id)) deletedCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                c.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                    .edit().remove("scam_history_json").apply()
                Toast.makeText(c, "$deletedCount message(s) deleted", Toast.LENGTH_SHORT).show()
                loadBlacklistLogs()
            }
        })

        if (blocked.isEmpty()) {
            llInboxList.addView(
                emptyState(
                    ctx,
                    "No messages found from blacklisted senders.\nThey may have been deleted."
                )
            )
            return
        }

        blocked.groupBy { it.sender.trim() }.forEach { (sender, msgs) ->
            llInboxList.addView(TextView(ctx).apply {
                text = "$sender — ${msgs.size} message(s)"
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(CipherPalette.danger)
                setBackgroundColor(CipherPalette.dangerContainer)
                val p = m(10, dp); setPadding(p, m(6, dp), p, m(6, dp))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(4, dp) }
            })
            msgs.forEach { msg ->
                val result = analyzeMessage(msg.sender, msg.body).toAnalysisResult()
                llInboxList.addView(buildConversationCard(ctx, msg, result))
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // MESSAGE OPTIONS — long-press from inbox card
    // ════════════════════════════════════════════════════════════════════

    private fun showMessageOptionsDialog(ctx: Context, msg: SmsEntry, result: AnalysisResult) {
        val options = arrayOf("Open Conversation", "Delete Conversation")
        AlertDialog.Builder(ctx)
            .setTitle("${msg.sender}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openConversation(msg.sender)
                    1 -> confirmDeleteConversation(ctx, msg)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Bulk-delete every SMS in a thread after explicit confirmation.
    // We confirm first because, unlike the old single-message delete, this can
    // wipe dozens of messages in one tap and there is no undo.
    private fun confirmDeleteConversation(ctx: Context, msg: SmsEntry) {
        val threadCount = readSmsThread(ctx, msg.sender).size
        val plural = if (threadCount != 1) "s" else ""
        AlertDialog.Builder(ctx)
            .setTitle("Delete entire conversation?")
            .setMessage(
                "This will delete all $threadCount message$plural with \"${msg.sender}\"." +
                        "\n\nThis cannot be undone."
            )
            .setPositiveButton("Delete All") { _, _ ->
                val deleted = deleteConversationFromPhone(ctx, msg.sender)
                // Log ONCE per conversation delete (not per message) so History
                // doesn't get spammed when nuking a large thread.
                logAction(ctx, msg.sender, msg.body, msg.date, "Conversation deleted")
                val note = when {
                    deleted > 0 -> "Deleted $deleted message${if (deleted != 1) "s" else ""} from \"${msg.sender}\""
                    !isDefaultSmsApp(ctx) -> "Set CHIPHERLOCK as default SMS app to delete from phone"
                    else -> "Nothing to delete"
                }
                Toast.makeText(ctx, note, Toast.LENGTH_LONG).show()
                loadInbox()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Deletes every SMS row (inbox + sent) whose address matches sender in one IPC.
    // Returns the number of rows actually removed from the system provider —
    // will be 0 if CHIPHERLOCK is not the default SMS app on API 19+.
    private fun deleteConversationFromPhone(ctx: Context, sender: String): Int {
        return try {
            ctx.contentResolver.delete(
                Uri.parse("content://sms"),
                "address = ?",
                arrayOf(sender)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    // Saves a user action (delete / unsend) to history — bypasses score threshold.
    private fun logAction(
        ctx: Context,
        sender: String,
        body: String,
        timestamp: Long,
        action: String
    ) {
        val breakdown = analyzeMessage(sender, body)
        val entry = ScamHistoryEntry(
            sender = sender,
            messagePreview = body.take(120),
            riskLevel = breakdown.level,
            riskScore = breakdown.total,
            category = breakdown.category,
            factors = breakdown.factors,
            timestamp = timestamp,
            source = action
        )
        val list = loadHistory(ctx)
        list.add(0, entry)
        if (list.size > 100) list.removeAt(list.size - 1)
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        ctx.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
            .edit().putString("scam_history_json", arr.toString()).apply()
    }

    // ════════════════════════════════════════════════════════════════════
    // CONVERSATION VIEW
    // ════════════════════════════════════════════════════════════════════

    // When a user taps a card from one of the inbox filter tabs, we open the
    // thread filtered to messages that match that tab's tier. This prevents
    // spoofed-sender messages (e.g. a phishing SMS claiming "From: BPI") from
    // contaminating the legit BPI conversation — they belong in their own
    // thread accessed from the Unknown tab, not mixed in with real bank alerts.
    //
    // [restrictToTier] = null preserves the legacy "show everything" behavior
    // (e.g. for the long-press dialog where context is ambiguous).
    private fun openConversation(sender: String, restrictToTier: SenderTrust? = null) {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        val blue = CipherPalette.primary
        val red = CipherPalette.danger

        panelInbox.visibility = View.GONE
        panelScanner.visibility = View.GONE
        panelQuarantine.visibility = View.GONE
        panelHistory.visibility = View.GONE
        panelConversation.visibility = View.VISIBLE
        panelConversation.removeAllViews()

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(blue)
            val p = m(10, dp); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(Button(ctx).apply {
            text = "← BACK"; textSize = 11f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = m(8, dp) }
            setOnClickListener { closeConversation() }
        })
        header.addView(TextView(ctx).apply {
            text = sender; textSize = 14f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(ctx).apply {
            text = "Block"; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(red)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, m(48, dp))
            setOnClickListener {
                val c = context ?: return@setOnClickListener
                saveToBlacklist(
                    c, BlacklistEntry(
                        sender = sender, reason = "Manual",
                        timestamp = System.currentTimeMillis()
                    )
                )
                Toast.makeText(c, "\"$sender\" blocked", Toast.LENGTH_SHORT).show()
                closeConversation()
                loadInbox()
                loadBlacklistSection()
            }
        })
        panelConversation.addView(header)

        val threadContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = m(8, dp); setPadding(p, p, p, p)
        }
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(threadContainer)
        }
        panelConversation.addView(scrollView)

        // If the caller asked for tier filtering, drop messages whose own
        // per-message tier (derived from their risk verdict) doesn't match.
        // A legit BPI thread should show ONLY messages where this sender
        // is acting as a legitimate VERIFIED service, not the SPOOFED scams
        // that share the same sender ID.

        val allMessages = readSmsThread(ctx, sender)
        val messages = if (restrictToTier == null) allMessages else allMessages.filter { entry ->
            // Look up the per-message risk by smsId — the authoritative
            // source. Falls back to historyVerdictFor only if the message
            // pre-dates the risk index (e.g. arrived before this build).
            val indexLevel = getMessageRisk(ctx, entry.smsId)
            val verdict = indexLevel ?: historyVerdictFor(ctx, SmsEntry(
                sender = sender,
                body   = entry.body,
                date   = entry.date,
                smsId  = -1L,
                type   = entry.type
            ))?.level
            val tier = classifySender(ctx, sender, verdict)
            // Outgoing messages (type 2) always belong with the legit side
            // — the user sent them, so they aren't impersonators of anyone.
            if (entry.type == 2) restrictToTier != SenderTrust.SPOOFED
            else tier == restrictToTier
        }
        if (messages.isEmpty()) {
            threadContainer.addView(
                emptyState(
                    ctx,
                    "No messages with this sender yet.\nSend one below!"
                )
            )
        } else {
            messages.forEach { entry ->
                threadContainer.addView(
                    buildThreadBubble(ctx, entry, sender, scrollView, threadContainer, dp)
                )
            }
        }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }

        // Phase 4.1 — No-promo warning banner
        // Shows only when vault is unlocked and no active promo detected.
        // Silently hidden if vault is locked (user hasn't authenticated yet).
        val noPromoPrefs = ctx.getSharedPreferences("load_tracker_prefs", Context.MODE_PRIVATE)
        val hasPromo     = noPromoPrefs.getString("active_promo_id", null) != null
        if (VaultKeyManager.isUnlocked() && !hasPromo) {
            val warnBanner = TextView(ctx).apply {
                text      = "No active promo detected — SMS costs ₱1.00 each. Tap to check your load."
                textSize  = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(CipherPalette.warning)
                val p = m(10, dp); setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            panelConversation.addView(warnBanner)
        }

        val sendBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CipherPalette.surface)
            val p = m(8, dp); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvCharCount = TextView(ctx).apply {
            text = "160"; textSize = 11f
            setTextColor(CipherPalette.textMuted)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = m(4, dp) }
        }
        val etMessage = EditText(ctx).apply {
            hint = "Type a message…"; textSize = 14f
            setSingleLine(false); maxLines = 3
            setBackgroundColor(CipherPalette.surface)
            filters = arrayOf(android.text.InputFilter.LengthFilter(160))
            val p = m(8, dp); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = m(4, dp) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val remaining = 160 - (s?.length ?: 0)
                    tvCharCount.text = remaining.toString()
                    tvCharCount.setTextColor(
                        if (remaining <= 20) CipherPalette.danger else CipherPalette.textMuted
                    )
                }
            })
        }
        sendBar.addView(etMessage)
        sendBar.addView(tvCharCount)
        // Phase 4.1 — Load Tracker button in conversation send bar
        sendBar.addView(Button(ctx).apply {
            text = "Load"
            textSize = 12f
            isAllCaps = false
            contentDescription = "Load balance"
            backgroundTintList = ColorStateList.valueOf(CipherPalette.warning)
            setTextColor(CipherPalette.onPrimary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (4 * dp).toInt() }
            setOnClickListener {
                val frag = LoadTrackerFragment()
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack("load_tracker")
                    .commit()
            }
        })
        panelConversation.addView(sendBar)
    }

    private fun closeConversation() {
        panelConversation.visibility = View.GONE
        panelConversation.removeAllViews()
        showTab("INBOX")
    }

    private fun readSmsThread(ctx: Context, sender: String): List<SmsThreadEntry> {
        val list = mutableListOf<SmsThreadEntry>()
        try {
            ctx.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "body", "date", "type"),
                "address = ?",
                arrayOf(sender),
                "date ASC"
            )?.use { c ->
                val cId = c.getColumnIndex("_id")
                val cBody = c.getColumnIndex("body")
                val cDate = c.getColumnIndex("date")
                val cType = c.getColumnIndex("type")
                while (c.moveToNext()) {
                    val type = if (cType >= 0) c.getInt(cType) else 1
                    if (type != 1 && type != 2) continue
                    list.add(
                        SmsThreadEntry(
                            smsId = if (cId >= 0) c.getLong(cId) else -1L,
                            body = c.getString(cBody) ?: "",
                            date = c.getLong(cDate),
                            type = type
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun buildThreadBubble(
        ctx: Context, entry: SmsThreadEntry, sender: String,
        scrollView: ScrollView, threadContainer: LinearLayout, dp: Float
    ): View {
        val isOutgoing = entry.type == 2
        val sdf = SimpleDateFormat("MMM dd  HH:mm", Locale.getDefault())
        val bubbleColor =
            if (isOutgoing) CipherPalette.primaryContainer else CipherPalette.surfaceHigh
        val textColor = CipherPalette.textPrimary
        val wrapGravity = if (isOutgoing) Gravity.END else Gravity.START

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = wrapGravity
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = m(8, dp)
                it.leftMargin = m(8, dp)
                it.rightMargin = m(8, dp)
            }
        }

        val maxBubbleWidth = (ctx.resources.displayMetrics.widthPixels * 0.75f).toInt()

        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pHoriz = m(14, dp)
            val pVert = m(10, dp)
            setPadding(pHoriz, pVert, pHoriz, pVert)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = m(18, dp).toFloat()
                setColor(bubbleColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        bubble.addView(TextView(ctx).apply {
            text = entry.body
            textSize = 15f
            setTextColor(textColor)
            setLineSpacing(0f, 1.2f)
            maxWidth = maxBubbleWidth
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        bubble.addView(TextView(ctx).apply {
            text = sdf.format(Date(entry.date))
            textSize = 10f
            gravity = Gravity.END
            setTextColor(if (isOutgoing) CipherPalette.textPrimary else CipherPalette.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = m(4, dp) }
        })

        bubble.setOnLongClickListener {
            val options = if (isOutgoing) arrayOf("Unsend", "Delete")
            else arrayOf("Delete")
            AlertDialog.Builder(ctx)
                .setTitle(if (isOutgoing) "Your message" else "From $sender")
                .setItems(options) { _, which ->
                    val c = context ?: return@setItems
                    val action = if (isOutgoing && which == 0) "Unsent" else "Deleted"
                    deleteFromPhone(c, entry.smsId)
                    addSoftDeleted(c, entry.smsId, sender, entry.body)
                    logAction(c, sender, entry.body, entry.date, action)
                    val label =
                        if (action.contains("Unsent")) "Message unsent" else "Message deleted"
                    Toast.makeText(c, label, Toast.LENGTH_SHORT).show()
                    threadContainer.removeView(wrapper)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        wrapper.addView(bubble)
        return wrapper
    }

    private fun sendSmsTo(
        ctx: Context, recipient: String, body: String,
        scrollView: ScrollView, threadContainer: LinearLayout, dp: Float
    ) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(recipient, null, body, null, null)

            // Phase 4.1 — Record SMS in encrypted load history
            // Reads active promo from SharedPreferences to log cost accurately.
            // Recipient is hashed inside recordSmsSent — raw number never stored.
            val ltPrefs    = ctx.getSharedPreferences("load_tracker_prefs", Context.MODE_PRIVATE)
            val promoId    = ltPrefs.getString("active_promo_id", "none") ?: "none"
            val promoName  = ltPrefs.getString("active_promo_name", "No promo") ?: "No promo"
            val telcoId    = ltPrefs.getString("active_telco", "UNKNOWN") ?: "UNKNOWN"
            // Cost: 0.0 if any unlimited promo active, 1.0 if regular load
            val smsCost    = if (promoId == "none") 1.0 else 0.0
            LoadTrackerFragment.recordSmsSent(ctx, recipient, promoId, promoName, smsCost, telcoId)


            LoadTrackerFragment.incrementSmsSent(ctx)

            val now = System.currentTimeMillis()

            try {
                val values = android.content.ContentValues().apply {
                    put("address", recipient)
                    put("body", body)
                    put("date", now)
                    put("type", 2)
                    put("read", 1)
                }
                ctx.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val sent = SmsThreadEntry(smsId = -1L, body = body, date = now, type = 2)
            threadContainer.addView(
                buildThreadBubble(ctx, sent, recipient, scrollView, threadContainer, dp)
            )
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            Toast.makeText(ctx, "Message sent", Toast.LENGTH_SHORT).show()

            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent("com.chipherlock.SMS_RECEIVED_REFRESH").apply {
                    putExtra("sender", recipient)
                })

        } catch (e: Exception) {
            Toast.makeText(ctx, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // SCANNER TAB
    // ════════════════════════════════════════════════════════════════════

    private fun setupScanner() {
        val ctx = context ?: return
        switchAutoQuarantine.isChecked = isAutoQuarantineEnabled(ctx)
        updateQuarantineToggleUI()
        switchAutoQuarantine.setOnCheckedChangeListener { _, checked ->
            val c = context ?: return@setOnCheckedChangeListener
            c.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(AUTO_QUARANTINE_KEY, checked).apply()
            updateQuarantineToggleUI()
            if (checked) bulkQuarantineHighRisk()
        }
        btnScanSms.setOnClickListener { scanSms() }
    }

    private fun updateQuarantineToggleUI() {
        val ctx = context ?: return
        if (isAutoQuarantineEnabled(ctx)) {
            tvAutoQuarantineStatus.text =
                "Active — HIGH risk messages will be sent to Q-Zone automatically."
            tvAutoQuarantineStatus.setTextColor(CipherPalette.success)
        } else {
            tvAutoQuarantineStatus.text = "OFF — HIGH risk messages will not be quarantined"
            tvAutoQuarantineStatus.setTextColor(CipherPalette.textMuted)
        }
    }

    private fun scanSms() {
        val ctx = context ?: return
        llScanResults.removeAllViews()
        tvSmsPlaceholder.visibility = View.GONE
        tvScanSummary.visibility = View.GONE
        hideAiStatus()

        val quarantine = loadQuarantine(ctx)
        val quarantinedIds = quarantine.map { it.smsId }.toSet()
        val quarantinedKeys = quarantine
            .map { it.sender.trim() + "|" + it.messageBody.take(60) }
            .toSet()

        val blacklistedSenders = loadBlacklist(ctx)
            .map { it.sender.trim().lowercase() }.toSet()

        val messages = readSmsInbox(ctx, 50).filter { msg ->
            msg.sender.trim().lowercase() !in blacklistedSenders &&
                    msg.smsId !in quarantinedIds &&
                    (msg.sender.trim() + "|" + msg.body.take(60)) !in quarantinedKeys
        }

        if (messages.isEmpty()) {
            tvSmsPlaceholder.text = "No messages found in inbox"
            tvSmsPlaceholder.visibility = View.VISIBLE
            return
        }

        // ── PHASE 1: Keyword scan (synchronous, instant) ──────────────────
        val keywordResults: List<Pair<SmsEntry, ScoreBreakdown>> =
            messages.map { msg -> msg to analyzeMessage(msg.sender, msg.body) }

        // Build the skip set — messages already fully verified by this pipeline.
        // • Q-Zone: already actioned; no point re-scanning.
        // • LOW cache: confirmed LOW by keyword + AI — skip forever.
        // MEDIUM/HIGH are intentionally excluded from the skip set so they
        // always re-verify; the AI might catch more next time.
        val lowCacheKeys = loadLowCache(ctx)
        val skipKeys = quarantinedKeys + lowCacheKeys

        val aiCandidates: List<Pair<SmsEntry, ScoreBreakdown>> =
            keywordResults.filter { (msg, _) ->
                (msg.sender.trim() + "|" + msg.body.take(60)) !in skipKeys
            }

        // ── PHASE 2: Render keyword-only results immediately ──────────────
        // The user sees instant feedback; AI re-renders when it returns.
        // History is NOT saved here — it's saved once after AI fusion to avoid
        // dedup conflicts between keyword-only and combined entries.
        renderResults(ctx, keywordResults, persist = false)

        if (aiCandidates.isEmpty()) {
            // Every message in this scan window is already in the LOW cache
            // or Q-Zone — zero Groq calls needed.
            showAiStatus(
                "All ${keywordResults.size} message(s) already verified — 0 Groq calls used",
                busy = false
            )
            // Write the per-message risk index so the conversation view can
            // tell spoofed messages apart from legit ones in the same thread.
            keywordResults.forEach { (msg, bd) -> setMessageRisk(ctx, msg.smsId, bd.level) }
            persistResults(ctx, keywordResults)
        }

        if (!GroqScamAnalyzer.isConfigured()) {
            showAiStatus(
                "AI disabled — paste your API key in GroqScamAnalyzer.kt",
                busy = false
            )
            keywordResults.forEach { (msg, bd) -> setMessageRisk(ctx, msg.smsId, bd.level) }
            persistResults(ctx, keywordResults)
            return
        }

        // ── PHASE 3: Parallel AI verification ─────────────────────────────
        val skipped = keywordResults.size - aiCandidates.size
        showAiStatus(
            if (skipped > 0)
                "Groq verifying ${aiCandidates.size} new — $skipped already cached…"
            else
                "Groq verifying ${aiCandidates.size} message(s)…",
            busy = true
        )

        lifecycleScope.launch {
            val aiByKey: Map<String, GroqScamAnalyzer.ScamResult> = withContext(Dispatchers.IO) {
                aiCandidates.map { (msg, _) ->
                    async {
                        val key = msg.smsId.toString() + "|" + msg.body.take(60)
                        val isContact = ContactsHelper.isContact(ctx, msg.sender)
                        key to GroqScamAnalyzer.analyze(msg.body, isContact = isContact)
                    }
                }.awaitAll().toMap()
            }

            if (_view == null) return@launch  // fragment destroyed mid-call

            // Fuse keyword + AI for every message that went through AI.
            // Cached/skipped messages keep their keyword breakdown as-is.
            val finalResults: List<Pair<SmsEntry, ScoreBreakdown>> =
                keywordResults.map { (msg, kwBd) ->
                    val key = msg.smsId.toString() + "|" + msg.body.take(60)
                    val ai = aiByKey[key]
                    msg to if (ai != null) fuseBreakdowns(kwBd, ai, msg.sender) else kwBd
                }

            val verifiedCount = aiByKey.values.count { it.success }
            val failedCount = aiByKey.values.count { !it.success }

            // Write each message's final risk into the per-message index so
            // the conversation viewer can distinguish legit vs spoofed
            // messages that share the same sender ID (e.g. real BPI alerts
            // vs phishing SMS spoofing "BPI"). This MUST happen BEFORE
            // renderResults — renderResults reads the index for routing.
            finalResults.forEach { (msg, bd) -> setMessageRisk(ctx, msg.smsId, bd.level) }

            // Re-render with combined scores; persist history + quarantine now
            renderResults(ctx, finalResults, persist = true)

            // Write confirmed LOWs to the cache — but ONLY when Groq
            // actually succeeded for that message. If AI failed (quota hit,
            // network error, etc.) the fallback is keyword-only score, which
            // can score a social-engineering scam as 0 = LOW even though it
            // was never truly analyzed. Caching those would permanently hide
            // them from future scans.
            //
            // aiByKey keys are  smsId|body.take(60)  (the AI lookup key).
            // LOW cache keys are sender|body.take(60) (the dedup key used
            // everywhere else). We map between them here.
            val aiSuccessIds = aiByKey
                .filter { (_, result) -> result.success }
                .keys   // "smsId|body.take(60)"
                .toSet()

            val newLowKeys = finalResults
                .filter { (msg, bd) ->
                    val aiKey = msg.smsId.toString() + "|" + msg.body.take(60)
                    bd.level == "LOW" && aiKey in aiSuccessIds
                }
                .map { (msg, _) -> msg.sender.trim() + "|" + msg.body.take(60) }

            if (newLowKeys.isNotEmpty()) {
                val cache = loadLowCache(ctx)
                cache.addAll(newLowKeys)
                saveLowCache(ctx, cache)
            }

            showAiStatus(
                when {
                    failedCount > 0 && verifiedCount > 0 ->
                        "AI verified $verifiedCount  •  $failedCount failed (used keyword score)"

                    failedCount > 0 ->
                        "AI unavailable — using keyword score only"

                    else ->
                        "AI verified $verifiedCount message(s)"
                },
                busy = false
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RENDER + PERSIST — extracted from scanSms so we can call them once
    // for the keyword-only pre-render and again with the AI-fused results.
    // ════════════════════════════════════════════════════════════════════

    private fun renderResults(
        ctx: Context,
        results: List<Pair<SmsEntry, ScoreBreakdown>>,
        persist: Boolean
    ) {
        llScanResults.removeAllViews()

        val autoQ = isAutoQuarantineEnabled(ctx)
        val high = mutableListOf<Pair<SmsEntry, ScoreBreakdown>>()
        val medium = mutableListOf<Pair<SmsEntry, ScoreBreakdown>>()
        val safe = mutableListOf<Pair<SmsEntry, ScoreBreakdown>>()
        var qCount = 0

        for ((msg, bd) in results) {
            when (bd.level) {
                "HIGH" -> {
                    high.add(msg to bd)
                    if (persist) {
                        upsertAlertHistory(ctx, msg.toHistoryEntry(bd, "Manual Scan"))
                        if (autoQ) {
                            // Never blacklist verified-service sender IDs
                            // (BPI, GCASH, GLOBE…). The scam is a spoof of
                            // that ID — blacklisting "BPI" would block the
                            // real bank's future alerts too.
                            saveToQuarantine(
                                ctx,
                                msg.toQuarantineEntry(bd),
                                autoBlacklist = !VerifiedSenders.isVerified(msg.sender)
                            ); qCount++
                        }
                    }
                }

                "MEDIUM" -> {
                    medium.add(msg to bd)
                    if (persist) {
                        upsertAlertHistory(ctx, msg.toHistoryEntry(bd, "Manual Scan"))
                        // Treat MEDIUM the same as HIGH when Auto-Q is on:
                        // quarantine + auto-blacklist so the sender can't reach
                        // the user again from the inbox. Verified sender IDs
                        // (BPI, GCASH, GLOBE…) are an exception — they get
                        // quarantined but NOT blacklisted, because the real
                        // brand also uses that sender ID legitimately.
                        if (autoQ) {
                            saveToQuarantine(
                                ctx,
                                msg.toQuarantineEntry(bd),
                                autoBlacklist = !VerifiedSenders.isVerified(msg.sender)
                            ); qCount++
                        }
                    }
                }

                else -> safe.add(msg to bd)
            }
        }

        val parts = mutableListOf(
            "${high.size} high",
            "${medium.size} suspicious",
            "${safe.size} safe"
        )
        if (qCount > 0) parts.add("$qCount quarantined")
        tvScanSummary.text = parts.joinToString(" | ")
        tvScanSummary.visibility = View.VISIBLE

        if (high.isNotEmpty()) {
            addSectionHeader("HIGH RISK — ${high.size} message(s)", "#FF5252")
            high.forEach { (msg, bd) ->
                llScanResults.addView(
                    buildDetailCard(
                        ctx,
                        msg.sender,
                        msg.body,
                        msg.date,
                        bd,
                        "Manual Scan"
                    )
                )
            }
        }
        if (medium.isNotEmpty()) {
            addSectionHeader("SUSPICIOUS — ${medium.size} message(s)", "#FF9800")
            medium.forEach { (msg, bd) ->
                llScanResults.addView(
                    buildDetailCard(
                        ctx,
                        msg.sender,
                        msg.body,
                        msg.date,
                        bd,
                        "Manual Scan"
                    )
                )
            }
        }
        if (safe.isNotEmpty()) {
            addSectionHeader("SAFE — ${safe.size} message(s)", "#4CAF50")
            safe.forEach { (msg, bd) ->
                llScanResults.addView(
                    buildDetailCard(
                        ctx,
                        msg.sender,
                        msg.body,
                        msg.date,
                        bd,
                        "Manual Scan"
                    )
                )
            }
        }

        if (qCount > 0) {
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent("com.chipherlock.QUARANTINE_UPDATED"))
        }
    }

    // Persists history + quarantine for results that were rendered with persist=false.
    // Used only when there's nothing for AI to verify (so phase 2 is final).
    private fun persistResults(ctx: Context, results: List<Pair<SmsEntry, ScoreBreakdown>>) {
        val autoQ = isAutoQuarantineEnabled(ctx)
        var qCount = 0
        for ((msg, bd) in results) {
            when (bd.level) {
                "HIGH" -> {
                    upsertAlertHistory(ctx, msg.toHistoryEntry(bd, "Manual Scan"))
                    if (autoQ) {
                        saveToQuarantine(
                            ctx,
                            msg.toQuarantineEntry(bd),
                            autoBlacklist = true
                        ); qCount++
                    }
                }

                "MEDIUM" -> {
                    upsertAlertHistory(ctx, msg.toHistoryEntry(bd, "Manual Scan"))
                    if (autoQ) {
                        saveToQuarantine(
                            ctx,
                            msg.toQuarantineEntry(bd),
                            autoBlacklist = true
                        ); qCount++
                    }
                }

                else -> { /* LOW: no persistence */
                }
            }
        }
        if (qCount > 0) {
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent("com.chipherlock.QUARANTINE_UPDATED"))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // FUSION — combine keyword score + AI score into one ScoreBreakdown
    //
    // Weighting: 40% keyword, 60% AI (per the Blueprint 2 spec).
    // The keyword score is normalized to 0..100 first (the legacy scorer
    // can occasionally exceed 100 by stacking many factors).
    //
    // The AI verdict is appended to the existing factors list as a special
    // ScoreFactor so the "Why this score?" panel automatically shows the
    // AI's reasoning alongside the keyword evidence. The panel's "Total"
    // line uses the combined score and is the authoritative number — the
    // individual factor points won't add up to the total in fused results
    // (keyword points are unweighted, AI shown at its 60% contribution),
    // but that's intentional: the breakdown is evidence, not arithmetic.
    // ════════════════════════════════════════════════════════════════════

    // fuseBreakdowns moved to companion object below so SmsReceiver
    // and the bulk-quarantine path can call it. Member call sites still
    // resolve via Kotlin's implicit companion lookup, so no caller changes.

    // ════════════════════════════════════════════════════════════════════
    // AI STATUS UI
    // ════════════════════════════════════════════════════════════════════

    private fun showAiStatus(message: String, busy: Boolean) {
        if (_view == null) return
        llAiStatus.visibility = View.VISIBLE
        aiLoadingBar.visibility = if (busy) View.VISIBLE else View.GONE
        tvAiStatus.text = message
    }

    private fun hideAiStatus() {
        if (_view == null) return
        llAiStatus.visibility = View.GONE
        aiLoadingBar.visibility = View.GONE
        tvAiStatus.text = ""
    }

    private fun addSectionHeader(title: String, hex: String) {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        llScanResults.addView(TextView(ctx).apply {
            text = title; textSize = 15f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(hex))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = m(12, dp); it.bottomMargin = m(4, dp) }
        })
    }

    // ════════════════════════════════════════════════════════════════════
    // DETAIL CARD — Scanner tab + History tab
    // Now accepts ScoreBreakdown and shows expandable "Why this score?" panel
    // ════════════════════════════════════════════════════════════════════

    private fun buildDetailCard(
        ctx: Context,
        sender: String, body: String, timestamp: Long,
        breakdown: ScoreBreakdown, source: String
    ): View {
        val dp = ctx.resources.displayMetrics.density
        val color = riskColor(breakdown.level)
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.resources.getDrawable(android.R.drawable.dialog_holo_light_frame, null)
            elevation = 4f * dp
            val p = m(12, dp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(12, dp) }

            // ── Level badge + sender ──
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                addView(TextView(ctx).apply {
                    text = "  ${breakdown.level}  "; textSize = 12f
                    setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                    setBackgroundColor(color)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = m(8, dp) }
                })
                addView(TextView(ctx).apply {
                    val contactName = ContactsHelper.getContactName(ctx, sender)
                    text = if (contactName != null) "$contactName  ($sender)"
                    else "$sender"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD); setTextColor(CipherPalette.textPrimary)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })

            // ── Category ──
            addView(TextView(ctx).apply {
                text = "${breakdown.category}"; textSize = 12f; setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(6, dp) }
            })

            // ── Timestamp + source ──
            addView(TextView(ctx).apply {
                text = "${sdf.format(Date(timestamp))}  ·  $source"
                textSize = 11f; setTextColor(CipherPalette.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(2, dp) }
            })

            // ── Message body ──
            addView(TextView(ctx).apply {
                text = "Message:"; textSize = 12f
                setTypeface(null, Typeface.BOLD); setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(8, dp) }
            })
            addView(previewBox(ctx, body, dp))

            // ── Risk score bar ──
            addView(riskScoreRow(ctx, breakdown.total, color, dp))

            // ── Score Breakdown panel (Blueprint 1) ──────────────────────
            // "How we decided" — shows keyword scan + AI double-check status,
            // with collapsible evidence list. Implemented in ScoreBreakdownUI
            // so Q-Zone shares the exact same look.
            if (breakdown.factors.isNotEmpty()) {
                addView(ScoreBreakdownUI.buildSimpleBreakdownPanel(ctx, breakdown))
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Q-ZONE TAB
    // ════════════════════════════════════════════════════════════════════

    private fun loadQZone() {
        val ctx = context ?: return
        NotificationCounter.markRead(ctx, SmsCategory.QZONE)
        updateDrawerHeader()
        val dp = ctx.resources.displayMetrics.density
        val isDefault = isDefaultSmsApp(ctx)
        llQuarantineList.removeAllViews()

        val entries = loadQuarantine(ctx)
        tvQuarantineCount.text =
            "${entries.size} quarantined message${if (entries.size != 1) "s" else ""}"

        if (!isDefault) {
            llQuarantineList.addView(buildDefaultSmsWarning(ctx, dp))
        }

        if (entries.isEmpty()) {
            tvQuarantinePlaceholder.visibility = View.VISIBLE
            btnDeleteAllQuarantine.isEnabled = false
            return
        }

        tvQuarantinePlaceholder.visibility = View.GONE
        btnDeleteAllQuarantine.isEnabled = true

        btnDeleteAllQuarantine.setOnClickListener {
            val c = context ?: return@setOnClickListener
            val list = loadQuarantine(c)



            var deletedCount = 0
            if (isDefaultSmsApp(c)) {
                list.forEach { if (deleteFromPhone(c, it.smsId)) deletedCount++ }
                list.forEach { addSoftDeleted(c, it.smsId, it.sender, it.messageBody) }
            }
            clearQuarantine(c)
            val msg = when {
                deletedCount > 0 -> "$deletedCount message(s) permanently deleted from phone"
                !isDefaultSmsApp(c) -> "Quarantine cleared — set CHIPHERLOCK as default SMS app to delete from phone"
                else -> "Quarantine cleared"
            }
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            loadQZone()
        }

        entries.forEach { llQuarantineList.addView(buildQZoneCard(ctx, it, isDefault)) }
    }

    private fun buildDefaultSmsWarning(ctx: Context, dp: Float): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CipherPalette.warningContainer)
            val p = m(12, dp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(12, dp) }

            addView(TextView(ctx).apply {
                text =
                    "Set CHIPHERLOCK as your default SMS app to permanently delete messages from your phone."
                textSize = 13f; setTextColor(CipherPalette.warning)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(Button(ctx).apply {
                text = "SET AS DEFAULT SMS APP"; textSize = 12f; setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(CipherPalette.warning)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(8, dp) }
                setOnClickListener { promptSetDefaultSmsApp() }
            })
        }
    }

    private fun buildQZoneCard(ctx: Context, entry: QuarantineEntry, isDefault: Boolean): View {
        val dp = ctx.resources.displayMetrics.density
        val purple = CipherPalette.warning
        val red = CipherPalette.danger
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.resources.getDrawable(android.R.drawable.dialog_holo_light_frame, null)
            elevation = 4f * dp
            val p = m(12, dp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(12, dp) }

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )

                addView(TextView(ctx).apply {
                    text = "  QUARANTINED  "; textSize = 11f
                    setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                    setBackgroundColor(purple)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = m(8, dp) }
                })
                addView(TextView(ctx).apply {
                    text = entry.sender; textSize = 13f
                    setTypeface(null, Typeface.BOLD); setTextColor(CipherPalette.textPrimary)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(ctx).apply {
                    text = "Delete"; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(red)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, m(48, dp))


                    setOnClickListener {
                        val c = context ?: return@setOnClickListener
                        val deletedFromPhone =
                            if (isDefaultSmsApp(c)) deleteFromPhone(c, entry.smsId) else false
                        addSoftDeleted(c, entry.smsId, entry.sender, entry.messageBody)
                        val list = loadQuarantine(c).toMutableList()
                        val key = entry.sender.trim() + "|" + entry.messageBody.take(60)
                        list.removeAll { it.sender.trim() + "|" + it.messageBody.take(60) == key }
                        saveQuarantineList(c, list)
                        val msg = when {
                            deletedFromPhone -> "Deleted from phone and quarantine"
                            !isDefaultSmsApp(c) -> "Removed from quarantine only — set CHIPHERLOCK as default SMS app to delete from phone"
                            else -> "Removed from quarantine"
                        }
                        Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                        loadQZone()
                    }
                })
            })

            addView(TextView(ctx).apply {
                text = "${entry.category}"; textSize = 12f; setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(6, dp) }
            })

            addView(TextView(ctx).apply {
                text = "${sdf.format(Date(entry.timestamp))}  ·  Auto-Quarantine"
                textSize = 11f; setTextColor(CipherPalette.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(2, dp) }
            })

            addView(TextView(ctx).apply {
                text = "Message:"; textSize = 12f
                setTypeface(null, Typeface.BOLD); setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(8, dp) }
            })

            addView(previewBox(ctx, entry.messageBody, dp))
            addView(riskScoreRow(ctx, entry.riskScore, red, dp))

            // ── "How we decided" panel ──────────────────────────────────
            // Uses ScoreBreakdownUI so Q-Zone shows the exact same layout as
            // the History tab: keyword scan + AI double-check status, with
            // an expandable list of triggered phrases. We reconstruct a
            // ScoreBreakdown from the stored QuarantineEntry fields because
            // ScoreBreakdownUI accepts that shape, not QuarantineEntry.
            if (entry.factors.isNotEmpty()) {
                val reconstructed = ScoreBreakdown(
                    factors = entry.factors,
                    total = entry.riskScore,
                    level = entry.riskLevel,
                    category = entry.category
                )
                addView(ScoreBreakdownUI.buildSimpleBreakdownPanel(ctx, reconstructed))
            }
            addView(buildReleaseButton(ctx, entry, dp))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // V7: Q-ZONE FRICTION RELEASE
    // ════════════════════════════════════════════════════════════════════

    private fun buildReleaseButton(ctx: Context, entry: QuarantineEntry, dp: Float): View {
        val green = CipherPalette.success
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (12*dp).toInt() }
        }
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()
            ).also { it.bottomMargin = (8*dp).toInt() }
            setBackgroundColor(CipherPalette.outlineSoft)
        })
        container.addView(TextView(ctx).apply {
            text = "Review the score breakdown above before releasing."
            textSize = 12f; setTextColor(CipherPalette.warning)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8*dp).toInt() }
        })
        container.addView(Button(ctx).apply {
            text = "Release to Inbox"
            textSize = 13f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(green)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val c = context ?: return@setOnClickListener
                AlertDialog.Builder(c)
                    .setTitle("Release from Q-Zone?")
                    .setMessage(
                        "Sender: ${entry.sender}\n" +
                                "Risk: ${entry.riskLevel} (${entry.riskScore}/100)\n\n" +
                                "Do you recognize this sender?\n\n" +
                                "You can also add \"${entry.sender}\" to your Allow List so future messages skip analysis."
                    )
                    .setPositiveButton("Release + Allow List") { _, _ ->
                        saveToAllowList(c, entry.sender)
                        releaseQZoneEntry(c, entry)
                    }
                    .setNeutralButton("Release Only") { _, _ ->
                        releaseQZoneEntry(c, entry)
                    }
                    .setNegativeButton("Keep in Q-Zone", null)
                    .show()
            }
        })
        return container
    }

    private fun releaseQZoneEntry(ctx: Context, entry: QuarantineEntry) {
        val key  = entry.sender.trim() + "|" + entry.messageBody.take(60)
        val list = loadQuarantine(ctx).toMutableList()
        list.removeAll { it.sender.trim() + "|" + it.messageBody.take(60) == key }
        saveQuarantineList(ctx, list)
        saveAlertToHistory(ctx, ScamHistoryEntry(
            sender         = entry.sender,
            messagePreview = "[Reviewed] " + entry.messageBody.take(120),
            riskScore      = entry.riskScore,
            riskLevel      = entry.riskLevel,
            category       = entry.category,
            factors        = entry.factors,
            timestamp      = System.currentTimeMillis(),
            source         = "Q-Zone Release"
        ))
        Toast.makeText(ctx, "Released to Inbox", Toast.LENGTH_SHORT).show()
        NotificationCounter.markRead(ctx, SmsCategory.QZONE)
        loadQZone()
        updateDrawerHeader()
    }


    // ════════════════════════════════════════════════════════════════════
    // HISTORY TAB
    // ════════════════════════════════════════════════════════════════════

    private fun loadHistoryTab() {
        val ctx = context ?: return
        llHistoryList.removeAllViews()
        val entries = loadHistory(ctx)
        tvHistoryCount.text = "${entries.size} alert${if (entries.size != 1) "s" else ""} logged"

        if (entries.isEmpty()) {
            llHistoryList.addView(
                emptyState(
                    ctx,
                    "No scam alerts recorded yet.\n\nRun a scan or wait for background detection."
                )
            )
        } else {
            entries.forEach { entry ->
                // Reconstruct a ScoreBreakdown from the stored entry so buildDetailCard
                // can render the "Why this score?" panel — factors come from JSON.
                val breakdown = ScoreBreakdown(
                    factors = entry.factors,
                    total = entry.riskScore,
                    level = entry.riskLevel,
                    category = entry.category
                )
                llHistoryList.addView(
                    buildDetailCard(
                        ctx, entry.sender, entry.messagePreview,
                        entry.timestamp, breakdown, entry.source
                    )
                )
            }
        }

        btnClearHistory.setOnClickListener {
            val c = context ?: return@setOnClickListener
            c.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                .edit().remove("scam_history_json").apply()
            clearLowCache(c)
            clearRiskIndex(c)
            clearSoftDeleted(c)
            Toast.makeText(
                c,
                "History, scan cache, risk index, and soft-delete log cleared",
                Toast.LENGTH_SHORT
            ).show()
            loadHistoryTab()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // AUTO-SCAN ON OPEN
    // ════════════════════════════════════════════════════════════════════

    private fun autoScanOnOpen() {
        val ctx = context ?: return
        try {
            val quarantine = loadQuarantine(ctx)
            val quarantinedIds = quarantine.map { it.smsId }.toSet()
            val quarantinedKeys = quarantine
                .map { it.sender.trim() + "|" + it.messageBody.take(60) }
                .toSet()

            val blacklistedSenders = loadBlacklist(ctx)
                .map { it.sender.trim().lowercase() }.toSet()

            for (msg in readSmsInbox(ctx, 50)) {
                if (msg.sender.trim().lowercase() in blacklistedSenders) continue
                val key = msg.sender.trim() + "|" + msg.body.take(60)
                if (msg.smsId in quarantinedIds || key in quarantinedKeys) continue
                val breakdown = analyzeMessage(msg.sender, msg.body)
                // Only log to history if the message arrived after installation.
                // Pre-existing messages still show risk badges in the inbox
                // but are never retroactively added to the threat history.
                if (breakdown.total >= 30 && msg.smsId > getOrSetBaseline(ctx)) {
                    saveAlertToHistory(ctx, msg.toHistoryEntry(breakdown, "Auto-Scan"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RISK ANALYSIS
    // ════════════════════════════════════════════════════════════════════

    // Thin shim — keeps inbox/conversation code that only needs level/category working unchanged.
    data class AnalysisResult(val score: Int, val level: String, val category: String)

    // Convenience extension so we can still call .toAnalysisResult() anywhere
    // that only needs level/category (inbox card labels, blacklist logs, etc.)
    private fun ScoreBreakdown.toAnalysisResult() = AnalysisResult(total, level, category)

    // type 1 = inbox (received), type 2 = sent (outgoing)
    data class SmsThreadEntry(val smsId: Long, val body: String, val date: Long, val type: Int)

    // ── analyzeMessage — returns full ScoreBreakdown ─────────────────────
    // Records every triggered keyword and structural signal as a ScoreFactor.
    private fun analyzeMessage(sender: String, body: String): ScoreBreakdown {
        val lower = body.lowercase()
        var score = 0
        val factors = mutableListOf<ScoreFactor>()
        var category = "Suspicious"

        val phKeywords = mapOf(
            "gcash" to 20, "maya" to 15, "nanalo" to 25, "premyo" to 25,
            "warrant" to 30, "arestuhin" to 30, "hulihin" to 30, "bayad" to 15,
            "padala" to 15, "pera" to 15, "bsp" to 15, "philhealth" to 15,
            "sss" to 15, "pagibig" to 15, "bir" to 15, "pulis" to 20,
            "nbi" to 20, "kaso" to 20, "imbestigasyon" to 25, "korte" to 25,
            "mandato" to 25, "suspendido" to 20, "i-verify" to 20,
            "mag-click" to 15, "mag-login" to 20
        )
        val genericKeywords = mapOf(
            "winner" to 25, "otp" to 20, "prize" to 20, "suspended" to 20,
            "blocked" to 20, "password" to 20, "congratulations" to 20,
            "bit.ly" to 25, "tinyurl" to 25, ".tk" to 20, "verify" to 15,
            "urgent" to 15, "claim" to 15, "bank" to 10, "account" to 10,
            "credit card" to 15, "atm" to 10, "pin" to 10, "ssn" to 20,
            "social security" to 20, "lottery" to 25, "free gift" to 20,
            "you have won" to 30, "click here" to 20, "limited time" to 15,
            "act now" to 15, "expires" to 10, "confirm" to 10, "login" to 15,
            "credential" to 20, "arrest" to 25, "legal action" to 25,
            "government" to 10, "official" to 10, "bdo" to 10, "metrobank" to 10,
            "bpi" to 10, "unionbank" to 10, "security alert" to 20,
            "unauthorized" to 20, "restore" to 15, "identity" to 15,
            "update" to 10, "access" to 10,
            // Government aid / voucher scams
            "ayuda" to 25, "voucher" to 20, "grocery voucher" to 30,
            "dswd" to 25, "kunin na" to 20, "bago maubos" to 25,
            "i-click" to 20, "naka-lista" to 20,
            // Suspicious TLDs
            ".online" to 30, ".xyz" to 25, ".site" to 25, ".click" to 30,
            ".live" to 20, ".info" to 15,
            // Hyphenated brand impersonation
            "bdo-" to 25, "bpi-" to 25, "gcash-" to 25, "dswd-" to 30,
            "maya-" to 25, "sss-" to 25, "philhealth-" to 25
        )

        // Category hints — first hit wins
        val categoryHints = mapOf(
            "gcash" to "Financial Fraud", "maya" to "Financial Fraud",
            "bayad" to "Financial Fraud", "padala" to "Financial Fraud",
            "pera" to "Financial Fraud", "bank" to "Financial Fraud",
            "bdo" to "Financial Fraud", "metrobank" to "Financial Fraud",
            "bpi" to "Financial Fraud", "unionbank" to "Financial Fraud",
            "account" to "Financial Fraud", "credit card" to "Financial Fraud",
            "atm" to "Financial Fraud",
            "nanalo" to "Prize Scam", "premyo" to "Prize Scam",
            "winner" to "Prize Scam", "prize" to "Prize Scam",
            "congratulations" to "Prize Scam", "lottery" to "Prize Scam",
            "free gift" to "Prize Scam", "you have won" to "Prize Scam",
            "warrant" to "Legal Threat", "arestuhin" to "Legal Threat",
            "hulihin" to "Legal Threat", "kaso" to "Legal Threat",
            "imbestigasyon" to "Legal Threat", "korte" to "Legal Threat",
            "mandato" to "Legal Threat", "arrest" to "Legal Threat",
            "legal action" to "Legal Threat",
            "bsp" to "Gov't Impersonation", "philhealth" to "Gov't Impersonation",
            "sss" to "Gov't Impersonation", "pagibig" to "Gov't Impersonation",
            "bir" to "Gov't Impersonation", "pulis" to "Gov't Impersonation",
            "nbi" to "Gov't Impersonation", "government" to "Gov't Impersonation",
            "official" to "Gov't Impersonation", "ssn" to "Gov't Impersonation",
            "social security" to "Gov't Impersonation",
            "bit.ly" to "Phishing Link", "tinyurl" to "Phishing Link",
            ".tk" to "Phishing Link", "click here" to "Phishing Link",
            "mag-click" to "Phishing Link",
            "otp" to "Credential Theft", "password" to "Credential Theft",
            "verify" to "Credential Theft", "i-verify" to "Credential Theft",
            "login" to "Credential Theft", "mag-login" to "Credential Theft",
            "credential" to "Credential Theft", "pin" to "Credential Theft",
            "suspended" to "Credential Theft", "suspendido" to "Credential Theft",
            "blocked" to "Credential Theft", "restore" to "Credential Theft",
            "identity" to "Credential Theft", "security alert" to "Credential Theft",
            "unauthorized" to "Credential Theft"
        )

        val initialCategory = "Suspicious"

        fun recordHit(keyword: String, points: Int) {
            score += points
            factors.add(ScoreFactor("\"$keyword\" detected", points))
            if (category == initialCategory) {
                category = categoryHints[keyword] ?: initialCategory
            }
        }

        phKeywords.forEach { (k, v) -> if (lower.contains(k)) recordHit(k, v) }
        genericKeywords.forEach { (k, v) -> if (lower.contains(k)) recordHit(k, v) }

        // Structural signals
        if (Regex("https?://").containsMatchIn(lower)) {
            score += 15
            factors.add(ScoreFactor("Suspicious URL detected", 15))
            if (category == initialCategory) category = "Phishing Link"
        }
        // Bracket-obfuscated URLs: domain[.]com or domain[dot]com
        if (lower.contains("[.]") || lower.contains("[dot]")) {
            score += 40
            factors.add(ScoreFactor("Obfuscated phishing link [.] detected", 40))
            if (category == initialCategory) category = "Phishing Link"
        }
        if (sender.trim().matches(Regex("\\d{4,6}"))) {
            score += 10
            factors.add(ScoreFactor("Short-code sender ($sender)", 10))
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


        // V7: Supplement with RuleEngine — sourced rules with BSP/NTC/GSMA provenance.
        RuleEngine.evaluate(body).forEach { match ->
            val label = "[${match.ruleId}] ${match.description}"
            if (factors.none { it.label == label }) {
                factors.add(ScoreFactor(label, match.weight))
                score += match.weight
                if (category == initialCategory && match.category.isNotEmpty()) {
                    category = match.category
                }
            }
        }

        // Phase 3.3: Country code validation
        val countryResult = PhoneCountryValidator.validate(sender, requireContext())
        if (countryResult.shouldPenalise) {
            score += countryResult.penalty
            factors.add(ScoreFactor(countryResult.factorLabel, countryResult.penalty))
        }

        // Cap raw score at 100 — stacking many keywords can push it over otherwise.
        val cappedScore = score.coerceIn(0, 100)
        val level = when {
            cappedScore >= 60 -> "HIGH"; cappedScore >= 30 -> "MEDIUM"; else -> "LOW"
        }

        return ScoreBreakdown(
            factors = factors,
            total = cappedScore,
            level = level,
            category = category
        )
    }

    private fun String.anyOf(vararg kw: String) = kw.any { contains(it) }

    // ════════════════════════════════════════════════════════════════════
    // SMS READERS
    // ════════════════════════════════════════════════════════════════════

    data class SmsEntry(
        val sender: String,
        val body: String,
        val date: Long,
        val smsId: Long = -1L,
        val type: Int = 1
    ) {
        fun toHistoryEntry(bd: ScoreBreakdown, source: String) = ScamHistoryEntry(
            sender = sender,
            messagePreview = body.take(120),
            riskLevel = bd.level,
            riskScore = bd.total,
            category = bd.category,
            factors = bd.factors,
            timestamp = date,
            source = source
        )

        fun toQuarantineEntry(bd: ScoreBreakdown) = QuarantineEntry(
            sender = sender,
            messageBody = body,
            riskLevel = bd.level,
            riskScore = bd.total,
            category = bd.category,
            timestamp = date,
            smsId = smsId,
            factors = bd.factors
        )
    }

    private fun readSmsInbox(ctx: Context, limit: Int): List<SmsEntry> {
        val list = mutableListOf<SmsEntry>()
        try {
            ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null, null, "date DESC"
            )?.use { c ->
                val cId = c.getColumnIndex("_id")
                val ci = c.getColumnIndex("address")
                val cb = c.getColumnIndex("body")
                val cd = c.getColumnIndex("date")
                var n = 0
                while (c.moveToNext() && n < limit) {
                    list.add(
                        SmsEntry(
                            sender = c.getString(ci) ?: "Unknown",
                            body = c.getString(cb) ?: "",
                            date = c.getLong(cd),
                            smsId = if (cId >= 0) c.getLong(cId) else -1L,
                            type = 1
                        )
                    )
                    n++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // Returns the list of cards to show in the Inbox.
    //
    // Card emission rule (per-message vs per-thread):
    //   • LOW-risk messages COLLAPSE by (sender, tier). All legit BPI alerts
    //     stack into one card, all GLOBE balance updates into one card, etc.
    //     Newest is the preview. Keeps the inbox tidy for high-volume legit
    //     senders.
    //   • MEDIUM and HIGH messages are NEVER collapsed — each scam gets its
    //     own card. This is the structural guarantee that a spoofed message
    //     cannot share a card with legitimate traffic of the same sender ID,
    //     no matter how the AI verdicts shake out.
    //
    // Returned list is newest-first.
    private fun readSmsAllThreads(ctx: Context): List<SmsEntry> {
        val out = mutableListOf<SmsEntry>()
        // Seen-group keys for LOW collapsing. MEDIUM/HIGH bypass this set
        // entirely so they always emit a fresh card.
        val seenLowGroups = HashSet<String>()
        try {
            ctx.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "address", "body", "date", "type"),
                null, null, "date DESC"   // newest first
            )?.use { c ->
                val cId = c.getColumnIndex("_id")
                val cAddr = c.getColumnIndex("address")
                val cBody = c.getColumnIndex("body")
                val cDate = c.getColumnIndex("date")
                val cType = c.getColumnIndex("type")

                while (c.moveToNext()) {
                    val sender = c.getString(cAddr) ?: "Unknown"
                    val body = c.getString(cBody) ?: ""
                    val date = c.getLong(cDate)
                    val smsId = if (cId >= 0) c.getLong(cId) else -1L
                    val type = if (cType >= 0) c.getInt(cType) else 1


                    // Skip rows the user soft-deleted. The row may still physically
                    // exist in content://sms (delete returned 0 or smsId was -1L at
                    // quarantine time), but CHIPHERLOCK treats it as gone.
                    if (isSoftDeleted(ctx, smsId, sender, body)) continue

                    // Outgoing messages (type 2) and messages with no recorded
                    // risk are treated as LOW for grouping — they cannot be
                    // "spoofed" because they're either user-sent or haven't
                    // been analyzed yet.
                    val riskLevel = if (type == 2) "LOW"
                    else getMessageRisk(ctx, smsId) ?: "LOW"
                    val tier = classifySender(ctx, sender, riskLevel)
                    val entry = SmsEntry(
                        sender = sender,
                        body = body,
                        date = date,
                        smsId = smsId,
                        type = type
                    )

                    if (riskLevel == "LOW") {
                        // Collapse LOW traffic by (sender, tier). First seen
                        // wins — and because we iterate date DESC, the
                        // "first" is the newest.
                        val groupKey = sender.trim().lowercase() + "|" + tier.name
                        if (seenLowGroups.add(groupKey)) {
                            out.add(entry)
                        }
                    } else {
                        // MEDIUM / HIGH — emit unconditionally, one card per
                        // message. This is what makes spoof isolation a
                        // structural property of the inbox.
                        out.add(entry)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    // ════════════════════════════════════════════════════════════════════
    // PHONE DELETION + DEFAULT SMS APP
    // ════════════════════════════════════════════════════════════════════

    private fun isDefaultSmsApp(ctx: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    private fun deleteFromPhone(ctx: Context, smsId: Long): Boolean {
        if (smsId == -1L) return false
        return try {
            ctx.contentResolver.delete(Uri.parse("content://sms/$smsId"), null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace(); false
        }
    }

    private fun promptSetDefaultSmsApp() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = ctx.getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                defaultSmsLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
            }
            defaultSmsLauncher.launch(intent)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════════════════════════════

    private fun riskColor(level: String) = when (level) {
        "HIGH" -> CipherPalette.danger
        "MEDIUM" -> CipherPalette.warning
        else -> CipherPalette.success
    }

    private fun m(dp: Int, density: Float) = (dp * density).toInt()

    private fun previewBox(ctx: Context, text: String, dp: Float) =
        TextView(ctx).apply {
            this.text = text; textSize = 12f
            typeface = Typeface.MONOSPACE; setTextColor(CipherPalette.textSecondary)
            setBackgroundColor(CipherPalette.surface)
            val p = m(8, dp); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = m(4, dp) }
        }

    private fun riskScoreRow(ctx: Context, score: Int, color: Int, dp: Float): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = m(8, dp) }
            addView(TextView(ctx).apply {
                text = "Risk Score:"; textSize = 11f
                setTypeface(null, Typeface.BOLD); setTextColor(CipherPalette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = m(8, dp) }
            })
            addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = minOf(score, 100)
                progressTintList = ColorStateList.valueOf(color)
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "$score/100"; textSize = 11f
                setTypeface(null, Typeface.BOLD); setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = m(8, dp) }
            })
        }

    private fun emptyState(ctx: Context, msg: String) =
        TextView(ctx).apply {
            text = msg; textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(CipherPalette.textMuted); setPadding(24, 64, 24, 64)
        }

    // ════════════════════════════════════════════════════════════════════
    // COMPANION OBJECT — all static persistence helpers
    // ════════════════════════════════════════════════════════════════════

    companion object {

        fun newInstance(args: Bundle?): SmsFragment {
            val f = SmsFragment()
            if (args != null) f.arguments = args
            return f
        }

        const val SETTINGS_PREFS = "chipherlock_settings"
        const val AUTO_QUARANTINE_KEY = "auto_quarantine_enabled"

        /**
         * Records the highest smsId present at first launch as a baseline.
         * Any message with smsId <= baseline is pre-existing and must not be
         * auto-quarantined, auto-blacklisted, or saved to history retroactively.
         * Called once — subsequent calls return the cached value immediately.
         */
        fun getOrSetBaseline(ctx: Context): Long {
            val prefs = ctx.getSharedPreferences("chipherlock_baseline", Context.MODE_PRIVATE)
            val existing = prefs.getLong("baseline_sms_id", -1L)
            if (existing != -1L) return existing
            val maxId = try {
                ctx.contentResolver.query(
                    android.net.Uri.parse("content://sms"),
                    arrayOf("MAX(_id)"),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
            } catch (e: Exception) { 0L }
            prefs.edit().putLong("baseline_sms_id", maxId).apply()
            return maxId
        }

        fun isAutoQuarantineEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(AUTO_QUARANTINE_KEY, false)

        private const val QUARANTINE_PREFS = "quarantine_prefs"
        private const val QUARANTINE_KEY = "quarantine_json"

        fun saveToQuarantine(ctx: Context, entry: QuarantineEntry, autoBlacklist: Boolean = false) {
            val list = loadQuarantine(ctx)
            if (entry.smsId != -1L && list.any { it.smsId == entry.smsId }) return
            list.add(0, entry)
            if (list.size > 100) list.removeAt(list.size - 1)
            saveQuarantineList(ctx, list)
            // Only auto-blacklist when Auto-Quarantine toggle is ON.
            // Manual scans quarantine without blacklisting the sender.
            if (autoBlacklist) {
                saveToBlacklist(
                    ctx, BlacklistEntry(
                        sender = entry.sender,
                        reason = "Auto-Scam",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        fun loadQuarantine(ctx: Context): MutableList<QuarantineEntry> {
            val json = ctx.getSharedPreferences(QUARANTINE_PREFS, Context.MODE_PRIVATE)
                .getString(QUARANTINE_KEY, null) ?: return mutableListOf()
            return try {
                val arr = JSONArray(json)
                MutableList(arr.length()) { QuarantineEntry.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                mutableListOf()
            }
        }

        fun saveQuarantineList(ctx: Context, list: List<QuarantineEntry>) {
            val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
            ctx.getSharedPreferences(QUARANTINE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(QUARANTINE_KEY, arr.toString()).apply()
        }

        fun clearQuarantine(ctx: Context) {
            ctx.getSharedPreferences(QUARANTINE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(QUARANTINE_KEY).apply()
        }

        fun saveAlertToHistory(ctx: Context, entry: ScamHistoryEntry) {
            val list = loadHistory(ctx)
            val key = entry.sender.trim() + "|" + entry.messagePreview.take(60)
            if (list.any { it.sender.trim() + "|" + it.messagePreview.take(60) == key }) return
            list.add(0, entry)
            if (list.size > 100) list.removeAt(list.size - 1)
            val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
            ctx.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                .edit().putString("scam_history_json", arr.toString()).apply()
        }

        // Like saveAlertToHistory, but REPLACES any existing entry with the
        // same sender+preview key. Used by the manual scan path so AI-fused
        // results overwrite the keyword-only entries written by SmsReceiver.
        fun upsertAlertHistory(ctx: Context, entry: ScamHistoryEntry) {
            val list = loadHistory(ctx)
            val key = entry.sender.trim() + "|" + entry.messagePreview.take(60)
            list.removeAll { it.sender.trim() + "|" + it.messagePreview.take(60) == key }
            list.add(0, entry)
            if (list.size > 100) list.removeAt(list.size - 1)
            val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
            ctx.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                .edit().putString("scam_history_json", arr.toString()).apply()
        }

        fun loadHistory(ctx: Context): MutableList<ScamHistoryEntry> {
            val json = ctx.getSharedPreferences("scam_history_prefs", Context.MODE_PRIVATE)
                .getString("scam_history_json", null) ?: return mutableListOf()
            return try {
                val arr = JSONArray(json)
                MutableList(arr.length()) { ScamHistoryEntry.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                mutableListOf()
            }
        }

        // ── Blacklist persistence ────────────────────────────────────────

        private const val BLACKLIST_PREFS        = "access_list_prefs"
        private const val BLACKLIST_KEY          = "access_list_json"
        private const val BLACKLIST_LEGACY_PREFS = "blacklist_prefs"
        private const val BLACKLIST_LEGACY_KEY   = "blacklist_json"

        fun saveToBlacklist(ctx: Context, entry: BlacklistEntry) {
            val list = loadBlacklist(ctx)
            if (list.any { it.sender.trim().lowercase() == entry.sender.trim().lowercase() }) return
            list.add(0, entry)
            val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
            ctx.getSharedPreferences(BLACKLIST_PREFS, Context.MODE_PRIVATE)
                .edit().putString(BLACKLIST_KEY, arr.toString()).apply()
        }

        fun loadBlacklist(ctx: Context): MutableList<BlacklistEntry> {
            val newPrefs = ctx.getSharedPreferences(BLACKLIST_PREFS, Context.MODE_PRIVATE)
            if (!newPrefs.contains(BLACKLIST_KEY)) {
                val legacyJson = ctx.getSharedPreferences(BLACKLIST_LEGACY_PREFS, Context.MODE_PRIVATE)
                    .getString(BLACKLIST_LEGACY_KEY, null)
                if (!legacyJson.isNullOrEmpty()) {
                    newPrefs.edit().putString(BLACKLIST_KEY, legacyJson).apply()
                }
            }
            val json = newPrefs.getString(BLACKLIST_KEY, null) ?: return mutableListOf()
            return try {
                val arr = JSONArray(json)
                MutableList(arr.length()) { BlacklistEntry.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                mutableListOf()
            }
        }

        fun isBlacklisted(ctx: Context, sender: String): Boolean =
            loadBlacklist(ctx).any {
                it.sender.trim().lowercase() == sender.trim().lowercase()
            }

        fun removeFromBlacklist(ctx: Context, sender: String) {
            val list = loadBlacklist(ctx)
            list.removeAll { it.sender.trim().lowercase() == sender.trim().lowercase() }
            val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
            ctx.getSharedPreferences(BLACKLIST_PREFS, Context.MODE_PRIVATE)
                .edit().putString(BLACKLIST_KEY, arr.toString()).apply()
        }

        fun saveToAllowList(ctx: Context, sender: String) {
            val prefs    = ctx.getSharedPreferences(BLACKLIST_PREFS, Context.MODE_PRIVATE)
            val existing = try { JSONArray(prefs.getString(BLACKLIST_KEY, "[]") ?: "[]") }
            catch (e: Exception) { JSONArray() }
            val alreadyListed = (0 until existing.length()).any {
                val e = existing.optJSONObject(it) ?: return@any false
                e.optString("sender", "").trim().equals(sender.trim(), ignoreCase = true)
            }
            if (alreadyListed) return
            val newEntry = JSONObject().apply {
                put("sender",    sender)
                put("type",      "ALLOW")
                put("reason",    "Q-Zone Release")
                put("timestamp", System.currentTimeMillis())
            }
            val newList = JSONArray().also { it.put(newEntry) }
            for (i in 0 until existing.length()) existing.optJSONObject(i)?.let { newList.put(it) }
            prefs.edit().putString(BLACKLIST_KEY, newList.toString()).apply()
        }


        // ── Verified-LOW cache ───────────────────────────────────────────────
        // Stores dedup keys (sender|body.take(60)) for messages that completed
        // the full keyword + AI pipeline and came back LOW. Persisted
        // indefinitely — confirmed-safe stays safe. Only LOW is cached;
        // MEDIUM and HIGH always re-verify on the next SCAN tap.

        private const val LOW_CACHE_PREFS = "low_cache_prefs"
        private const val LOW_CACHE_KEY   = "low_cache_json"

        fun loadLowCache(ctx: Context): MutableSet<String> {
            val json = ctx.getSharedPreferences(LOW_CACHE_PREFS, Context.MODE_PRIVATE)
                .getString(LOW_CACHE_KEY, null) ?: return mutableSetOf()
            return try {
                val arr = JSONArray(json)
                MutableList(arr.length()) { arr.getString(it) }.toMutableSet()
            } catch (e: Exception) { mutableSetOf() }
        }

        fun saveLowCache(ctx: Context, keys: Set<String>) {
            val arr = JSONArray().also { a -> keys.forEach { a.put(it) } }
            ctx.getSharedPreferences(LOW_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(LOW_CACHE_KEY, arr.toString()).apply()
        }

        fun clearLowCache(ctx: Context) {
            ctx.getSharedPreferences(LOW_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(LOW_CACHE_KEY).apply()
        }

        // ── Per-message risk index ─────────────────────────────────────
        // Maps an SMS row's smsId to the final risk level we determined
        // for it ("HIGH" / "MEDIUM" / "LOW"). This is the single source of
        // truth for "what is this individual message's risk?", independent
        // of the History tab (which omits LOW) and the LOW cache (which is
        // sender|body-keyed, not smsId-keyed).
        //
        // Why we need this: incoming SMS share a thread by sender ID, so a
        // spoofed message with From: "BPI" lives in the same OS-level thread
        // as the legit BPI alerts. To prevent thread contamination, we look
        // up each message's smsId in this index and filter the displayed
        // conversation by tier. Without the index, we'd have to reconstruct
        // per-message verdicts from string-matched History entries, which is
        // brittle and silently fails for LOW messages.
        //
        // Index is JSON: { "12345": "HIGH", "12346": "LOW", ... }
        private const val RISK_INDEX_PREFS = "risk_index_prefs"
        private const val RISK_INDEX_KEY   = "risk_index_json"

        fun setMessageRisk(ctx: Context, smsId: Long, level: String) {
            if (smsId == -1L) return  // can't index messages without an smsId
            val prefs = ctx.getSharedPreferences(RISK_INDEX_PREFS, Context.MODE_PRIVATE)
            val json  = prefs.getString(RISK_INDEX_KEY, "{}") ?: "{}"
            val obj   = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
            obj.put(smsId.toString(), level)
            prefs.edit().putString(RISK_INDEX_KEY, obj.toString()).apply()
        }

        fun getMessageRisk(ctx: Context, smsId: Long): String? {
            if (smsId == -1L) return null
            val prefs = ctx.getSharedPreferences(RISK_INDEX_PREFS, Context.MODE_PRIVATE)
            val json  = prefs.getString(RISK_INDEX_KEY, "{}") ?: "{}"
            return try {
                val obj = JSONObject(json)
                if (obj.has(smsId.toString())) obj.getString(smsId.toString()) else null
            } catch (e: Exception) { null }
        }

        fun clearRiskIndex(ctx: Context) {
            ctx.getSharedPreferences(RISK_INDEX_PREFS, Context.MODE_PRIVATE)
                .edit().remove(RISK_INDEX_KEY).apply()
        }

        // Combines a keyword ScoreBreakdown with a Groq ScamResult into one
        // final breakdown.
        //
        // Weighting depends on whether the sender is a verified service
        // (BPI, GCASH, GLOBE, GRAB, LBC, etc — see VerifiedSenders.kt):
        //   • Unknown sender → 40% keyword + 60% AI  (legacy behavior)
        //   • Verified sender → 20% keyword + 80% AI
        //
        // Why the flip for verified senders: keyword scoring is prone to
        // false positives on legitimate bank/wallet alerts. A real BPI
        // message contains "bpi" + "account" + amount-like text, which
        // adds up to MEDIUM on keywords alone — even though AI correctly
        // reads it as a routine transaction notification. Reducing the
        // keyword weight lets AI's "this is a real bank alert" verdict
        // dominate while still allowing some keyword influence in case
        // the sender ID was spoofed and the body really IS phishing.
        //
        // Public so SmsReceiver (incoming-SMS auto-quarantine) and the
        // bulk toggle-on path can use the same fusion as the manual scan.
        fun fuseBreakdowns(
            keyword: ScoreBreakdown,
            ai: GroqScamAnalyzer.ScamResult,
            sender: String? = null
        ): ScoreBreakdown {
            val keywordNormalized = keyword.total.coerceIn(0, 100)
            val isVerified = sender != null && VerifiedSenders.isVerified(sender)
            val keywordWeight = if (isVerified) 0.2 else 0.4
            val aiWeight = if (isVerified) 0.8 else 0.6

            val combinedTotal: Int = if (ai.success) {
                (keywordNormalized * keywordWeight + ai.aiScore * aiWeight)
                    .toInt().coerceIn(0, 100)
            } else {
                // AI failed → keyword score IS the total, but if sender is
                // verified we still soften it (×0.5) because we know keyword
                // tends to false-positive on these senders and we have no
                // counter-evidence from AI to fix it.
                if (isVerified) (keywordNormalized * 0.5).toInt() else keywordNormalized
            }

            // Combined risk bands (looser than keyword-only bands because
            // AI adds confidence: 40/70 vs the legacy 30/60).
            val combinedLevel = when {
                combinedTotal >= 70 -> "HIGH"
                combinedTotal >= 40 -> "MEDIUM"
                else -> "LOW"
            }

            val fusedFactors = keyword.factors.toMutableList()
            if (ai.success) {
                val aiContribution = (ai.aiScore * aiWeight).toInt()
                fusedFactors.add(
                    ScoreFactor(
                        label = if (isVerified)
                            "AI verdict: ${ai.risk} — ${ai.reason} (verified sender: AI weighted 80%)"
                        else
                            "AI verdict: ${ai.risk} — ${ai.reason}",
                        points = aiContribution
                    )
                )
            } else {
                fusedFactors.add(
                    ScoreFactor(
                        label = "AI unavailable — keyword score only (${ai.reason})",
                        points = 0
                    )
                )
            }

            // V7: AI category always wins when AI succeeds.
            // The keyword scanner uses pattern matching and gets things like
            // "Financial Fraud" for any message mentioning "account" or "bdo" —
            // even social engineering scams with no financial keywords at all.
            // Groq reads the message contextually and picks the correct attack type.
            val category = when {
                ai.success && ai.category.isNotBlank() && ai.category != "New Message" ->
                    ai.category
                ai.success && ai.risk == "HIGH" ->
                    "AI-Flagged Scam"
                keyword.category != "Suspicious" && keyword.category != "New Message" ->
                    keyword.category
                else ->
                    "Suspicious"
            }

            return ScoreBreakdown(
                factors = fusedFactors,
                total = combinedTotal,
                level = combinedLevel,
                category = category
            )
        }
        // ────────────────────────────────────────────────────────────────
// SOFT-DELETE LEDGER
// Messages the user explicitly deleted from CHIPHERLOCK that we
// could NOT physically remove from content://sms (e.g. the smsId
// lookup raced the SMS provider write, or the delete returned 0
// rows for any reason). readSmsAllThreads skips any row that
// matches either by smsId or by sender|body-prefix key, so the
// ghost-card cycle is broken regardless of why the OS-level
// delete failed.
// ────────────────────────────────────────────────────────────────
        private const val SOFT_DELETE_PREFS    = "soft_delete_prefs"
        private const val SOFT_DELETE_IDS_KEY  = "soft_delete_ids"
        private const val SOFT_DELETE_KEYS_KEY = "soft_delete_keys"

        fun addSoftDeleted(ctx: Context, smsId: Long, sender: String, body: String) {
            val prefs = ctx.getSharedPreferences(SOFT_DELETE_PREFS, Context.MODE_PRIVATE)
            // getStringSet returns a non-mutable view — copy before editing.
            val ids  = prefs.getStringSet(SOFT_DELETE_IDS_KEY, emptySet())?.toMutableSet()
                ?: mutableSetOf()
            val keys = prefs.getStringSet(SOFT_DELETE_KEYS_KEY, emptySet())?.toMutableSet()
                ?: mutableSetOf()
            if (smsId != -1L) ids.add(smsId.toString())
            keys.add(sender.trim() + "|" + body.take(60))
            prefs.edit()
                .putStringSet(SOFT_DELETE_IDS_KEY,  ids)
                .putStringSet(SOFT_DELETE_KEYS_KEY, keys)
                .apply()
        }

        fun isSoftDeleted(ctx: Context, smsId: Long, sender: String, body: String): Boolean {
            val prefs = ctx.getSharedPreferences(SOFT_DELETE_PREFS, Context.MODE_PRIVATE)
            val ids   = prefs.getStringSet(SOFT_DELETE_IDS_KEY,  emptySet()) ?: emptySet()
            val keys  = prefs.getStringSet(SOFT_DELETE_KEYS_KEY, emptySet()) ?: emptySet()
            if (smsId != -1L && smsId.toString() in ids) return true
            return (sender.trim() + "|" + body.take(60)) in keys
        }

        fun clearSoftDeleted(ctx: Context) {
            ctx.getSharedPreferences(SOFT_DELETE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(SOFT_DELETE_IDS_KEY)
                .remove(SOFT_DELETE_KEYS_KEY)
                .apply()
        }
    }

}