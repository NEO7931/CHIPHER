package com.chipherlock.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class VaultFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: VaultAdapter

    private val entries = mutableListOf<PasswordEntry>()

    // REM-004 — clipboard wipe scheduler. Use the main looper so cancel() is race-free.
    private val clipboardWipeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClipboardWipe: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_vault, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rvVault)
        fabAdd       = view.findViewById(R.id.fabAddPassword)
        tvEmpty      = view.findViewById(R.id.tvVaultEmpty)

        adapter = VaultAdapter(
            entries,
            onCopy   = { e -> copyToClipboard(e.password, showToast = true) },
            onDelete = { e -> confirmDelete(e) },
            onEdit   = { e -> showAddEditDialog(e) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener {
            // Auto-launch the app picker first so the user just taps and picks.
            // showAddEditDialog receives a "seed" PasswordEntry that pre-fills
            // the service name and package; user can still edit everything.
            showAppPicker { appName, packageName ->
                val seed = PasswordEntry(
                    service     = appName,
                    username    = "",
                    password = "".toCharArray(),
                    packageName = packageName,
                    timestamp   = System.currentTimeMillis()
                )
                showAddEditDialog(seed, forceAdd = true)
            }
        }
        fabAdd.setOnLongClickListener { showQuickGenerateDialog(); true }

        loadEntries()
        refreshEmpty()
    }

    override fun onDestroyView() {
        // REM-001 — wipe every CharArray before the fragment view is reclaimed.
        entries.forEach { it.wipe() }
        entries.clear()
        // REM-004 — cancel any pending clipboard wipe.
        pendingClipboardWipe?.let { clipboardWipeHandler.removeCallbacks(it) }
        pendingClipboardWipe = null
        super.onDestroyView()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App Picker — installed apps + curated popular apps, sectioned
    // ─────────────────────────────────────────────────────────────────────────

    private data class AppInfo(
        val name: String,
        val packageName: String,
        val isInstalled: Boolean = true
    )

    // Curated list: Social, Banking, Streaming, Shopping, Email
    // Apps already installed on the device are promoted to the Installed section.
    private val popularApps = listOf(
        // ── Social & Messaging ──────────────────────────────────────────────
        AppInfo("Facebook",          "com.facebook.katana",          false),
        AppInfo("Instagram",         "com.instagram.android",        false),
        AppInfo("TikTok",            "com.zhiliaoapp.musically",     false),
        AppInfo("X (Twitter)",       "com.twitter.android",          false),
        AppInfo("Snapchat",          "com.snapchat.android",         false),
        AppInfo("LinkedIn",          "com.linkedin.android",         false),
        AppInfo("Pinterest",         "com.pinterest",                false),
        AppInfo("Reddit",            "com.reddit.frontpage",         false),
        AppInfo("Discord",           "com.discord",                  false),
        AppInfo("Telegram",          "org.telegram.messenger",       false),
        AppInfo("WhatsApp",          "com.whatsapp",                 false),
        AppInfo("Messenger",         "com.facebook.orca",            false),
        AppInfo("Viber",             "com.viber.voip",               false),
        AppInfo("Signal",            "org.thoughtcrime.securesms",   false),
        AppInfo("Skype",             "com.skype.raider",             false),
        // ── Email ───────────────────────────────────────────────────────────
        AppInfo("Gmail",             "com.google.android.gm",        false),
        AppInfo("Outlook",           "com.microsoft.office.outlook", false),
        AppInfo("Yahoo Mail",        "com.yahoo.mobile.client.android.mail", false),
        AppInfo("ProtonMail",        "ch.protonmail.android",        false),
        AppInfo("Spark Mail",        "com.readdle.spark",            false),
        // ── Streaming ───────────────────────────────────────────────────────
        AppInfo("Netflix",           "com.netflix.mediaclient",      false),
        AppInfo("YouTube",           "com.google.android.youtube",   false),
        AppInfo("Spotify",           "com.spotify.music",            false),
        AppInfo("Disney+",           "com.disney.disneyplus",        false),
        AppInfo("HBO Max",           "com.hbo.hbomax",               false),
        AppInfo("Apple TV",          "com.apple.atve.androidtv.appletv", false),
        AppInfo("Amazon Prime Video","com.amazon.avod.thirdpartyclient", false),
        AppInfo("Twitch",            "tv.twitch.android.app",        false),
        AppInfo("SoundCloud",        "com.soundcloud.android",       false),
        AppInfo("KKBOX",             "com.skysoft.kkbox.android",    false),
        // ── Shopping ────────────────────────────────────────────────────────
        AppInfo("Lazada",            "com.lazada.android",           false),
        AppInfo("Shopee",            "com.shopee.ph",                false),
        AppInfo("Zalora",            "com.zalora.android",           false),
        AppInfo("Amazon Shopping",   "com.amazon.mShop.android.shopping", false),
        AppInfo("eBay",              "com.ebay.mobile",              false),
        AppInfo("Shein",             "com.zzkko",                    false),
        AppInfo("Temu",              "com.einnovation.temu",         false),
        // ── Banking & Finance ────────────────────────────────────────────────
        AppInfo("GCash",             "com.globe.gcash.android",      false),
        AppInfo("Maya",              "com.paymaya",                  false),
        AppInfo("BDO Digital Banking","com.bdo.retail",              false),
        AppInfo("BPI Mobile",        "com.bpi.ng.app",               false),
        AppInfo("UnionBank Online",  "com.unionbankph.corporate",    false),
        AppInfo("Metrobank App",     "com.mbtc.online",              false),
        AppInfo("RCBC DiskarTech",   "ph.rcbc.diskartech",           false),
        AppInfo("Landbank iAccess",  "com.lbp.lbpiAccessApp",        false),
        AppInfo("PNB Mobile Banking","com.pnb.mobile",               false),
        AppInfo("PayPal",            "com.paypal.android.p2pmobile", false),
        AppInfo("Binance",           "com.binance.dev",              false),
        AppInfo("Coins.ph",          "asia.coins.mobile",            false),
        // ── Other Accounts ───────────────────────────────────────────────────
        AppInfo("Google Account",    "com.google.android.googlequicksearchbox", false),
        AppInfo("Apple ID",          "com.apple.mobilemail",         false),
        AppInfo("Microsoft Account", "com.microsoft.intune.mam",     false),
        AppInfo("Grab",              "com.grabtaxi.passenger",       false),
        AppInfo("foodpanda",         "com.global.foodpanda.android", false),
        AppInfo("Uber",              "com.ubercab",                  false),
        AppInfo("Airbnb",            "com.airbnb.android",           false),
        AppInfo("Booking.com",       "com.booking",                  false),
        AppInfo("Agoda",             "com.agoda.mobile.consumer.android", false),
        AppInfo("Canva",             "com.canva.editor",             false),
        AppInfo("Adobe Acrobat",     "com.adobe.reader",             false),
        AppInfo("Dropbox",           "com.dropbox.android",          false),
        AppInfo("Steam",             "com.valvesoftware.android.steam.community", false),
        AppInfo("Mobile Legends",    "com.mobile.legends",           false),
        AppInfo("PUBG Mobile",       "com.tencent.ig",               false),
        AppInfo("Roblox",            "com.roblox.client",            false)
    )

    private fun getInstalledApps(): List<AppInfo> {
        val pm              = requireContext().packageManager
        val installedPkgs   = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map    { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName, isInstalled = true) }
            .sortedBy { it.name.lowercase() }
        return installedPkgs
    }

    /**
     * Builds two sections:
     *  • Installed Apps  — real device apps (popular ones promoted here if installed)
     *  • Popular Apps    — curated list, minus anything already in Installed
     *
     * Returns a flat list where section headers are represented by AppInfo with
     * packageName == "" (used by the adapter to render a header row, not a tappable item).
     */
    private fun buildPickerList(): List<AppInfo> {
        val installed     = getInstalledApps()
        val installedPkgs = installed.map { it.packageName }.toSet()

        // Popular apps not yet installed
        val popularOnly = popularApps
            .filter { it.packageName !in installedPkgs }
            .sortedBy { it.name.lowercase() }

        val result = mutableListOf<AppInfo>()
        if (installed.isNotEmpty()) {
            result += AppInfo("── Installed Apps ──", "", isInstalled = true)  // header
            result += installed
        }
        if (popularOnly.isNotEmpty()) {
            result += AppInfo("── Popular Apps ──", "", isInstalled = false)   // header
            result += popularOnly
        }
        return result
    }

    /** Flat list item: header sentinel has empty packageName. */
    private fun AppInfo.isHeader() = packageName.isEmpty()

    /**
     * Shows a searchable, sectioned list of apps.
     * [onPicked] is called with (appName, packageName) when user selects one.
     */
    private fun showAppPicker(onPicked: (appName: String, packageName: String) -> Unit) {
        val ctx  = requireContext()
        val d    = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val allItems    = buildPickerList()
        val filtered    = allItems.toMutableList()

        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Search field
        val etSearch = EditText(ctx).apply {
            hint      = "🔍  Search apps..."
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        container.addView(etSearch)

        // Divider
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        // ListView
        val listView = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
        }

        val listAdapter = object : ArrayAdapter<AppInfo>(ctx, 0, filtered) {

            private val TYPE_HEADER = 0
            private val TYPE_ITEM   = 1

            override fun getViewTypeCount() = 2
            override fun getItemViewType(pos: Int) = if (filtered[pos].isHeader()) TYPE_HEADER else TYPE_ITEM
            override fun isEnabled(pos: Int)       = !filtered[pos].isHeader()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = filtered[position]

                if (app.isHeader()) {
                    // Section header row
                    val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                        setPadding(dp(16), dp(10), dp(16), dp(6))
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#1976D2"))
                        setBackgroundColor(Color.parseColor("#F5F5F5"))
                        isAllCaps = true
                    }
                    tv.text = app.name
                    return tv
                }

                // Regular app row — reuse simple_list_item_2
                val v = convertView
                    ?: android.view.LayoutInflater.from(ctx)
                        .inflate(android.R.layout.simple_list_item_2, parent, false)

                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = app.name; textSize = 14f
                    setTextColor(Color.parseColor("#212121"))
                }
                v.findViewById<TextView>(android.R.id.text2).apply {
                    // Show "📱 Installed" badge for device apps, package name for popular
                    text = if (app.isInstalled) "📱 Installed · ${app.packageName}"
                    else app.packageName
                    textSize = 11f
                    setTextColor(
                        if (app.isInstalled) Color.parseColor("#388E3C")
                        else                 Color.parseColor("#9E9E9E")
                    )
                }
                return v
            }
        }

        listView.adapter = listAdapter
        container.addView(listView)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Select App")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        // Live search — when searching, flatten headers out so results are clean
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().lowercase().trim()
                filtered.clear()
                if (query.isEmpty()) {
                    filtered.addAll(allItems)
                } else {
                    // No section headers while searching — just matching items
                    filtered.addAll(
                        allItems.filter { !it.isHeader() &&
                                (it.name.lowercase().contains(query) ||
                                        it.packageName.lowercase().contains(query))
                        }
                    )
                }
                listAdapter.notifyDataSetChanged()
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val app = filtered[position]
            if (!app.isHeader()) {
                onPicked(app.name, app.packageName)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Add / Edit dialog
    // ─────────────────────────────────────────────────────────────────────────
    private fun showAddEditDialog(existing: PasswordEntry?, forceAdd: Boolean = false) {
        val ctx    = requireContext()
        val isEdit = existing != null && !forceAdd
        val d      = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll    = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        scroll.addView(container)

        fun field(label: String, hint: String, inputType: Int): EditText {
            container.addView(TextView(ctx).apply {
                text = label; textSize = 12f
                setTextColor(Color.parseColor("#757575"))
            })
            return EditText(ctx).apply {
                this.hint = hint; this.inputType = inputType
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }.also { container.addView(it) }
        }

        val etService  = field("Service / App Name *", "e.g. Gmail, BDO Online, Facebook",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val etUsername = field("Username / Email", "e.g. juan@email.com",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

        // Password row
        container.addView(TextView(ctx).apply {
            text = "Password *"; textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        })
        val passwordRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val etPassword = EditText(ctx).apply {
            hint = "Enter or generate a password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface  = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        var pwVisible = false
        val btnEye = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setImageResource(R.drawable.ic_visibility); background = null
            imageTintList = ColorStateList.valueOf(Color.parseColor("#757575"))
        }
        btnEye.setOnClickListener {
            pwVisible = !pwVisible
            etPassword.inputType = if (pwVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            etPassword.setSelection(etPassword.text.length)
            btnEye.setImageResource(if (pwVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        }
        passwordRow.addView(etPassword); passwordRow.addView(btnEye)
        container.addView(passwordRow)

        // Generate button
        val btnGenerate = Button(ctx).apply {
            text = "🎲 Generate Strong Password"; textSize = 12f
            setTextColor(Color.parseColor("#1976D2")); background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        container.addView(btnGenerate)

        // Strength bar
        val strengthRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0E0E0"))
        }
        val tvStrengthLabel = TextView(ctx).apply {
            text = "—"; textSize = 12f; setTypeface(null, Typeface.BOLD); minWidth = dp(72)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
        strengthRow.addView(progressBar); strengthRow.addView(tvStrengthLabel)
        container.addView(strengthRow)

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        val tvPositives   = TextView(ctx).apply { textSize = 11f; setTextColor(Color.parseColor("#4CAF50")); setLineSpacing(0f, 1.4f); layoutParams = lp }
        val tvSuggestions = TextView(ctx).apply { textSize = 11f; setTextColor(Color.parseColor("#FF9800")); setLineSpacing(0f, 1.4f); layoutParams = lp }
        container.addView(tvPositives); container.addView(tvSuggestions)

        // Divider
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(16) }
        })

        // ── App Picker — replaces package name text field ─────────────────────
        container.addView(TextView(ctx).apply {
            text = "Link to App (for auto-fill)"; textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        })

        // Stores the actual package name silently
        var selectedPackageName = existing?.packageName ?: ""

        // Shows the human-readable app name
        val tvSelectedApp = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#212121"))
            text = if (selectedPackageName.isNotEmpty()) {
                // Try to resolve existing package name back to app label
                try {
                    requireContext().packageManager
                        .getApplicationLabel(
                            requireContext().packageManager.getApplicationInfo(selectedPackageName, 0)
                        ).toString()
                } catch (e: Exception) { selectedPackageName }
            } else "No app linked"
        }


        val swCanary = android.widget.Switch(ctx).apply {
            text = "🪤 Mark as canary (alert on use)"
            textSize = 12f
            isChecked = existing?.isCanary ?: false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(swCanary)
        container.addView(TextView(ctx).apply {
            text = "💡 Use a real but monitored throwaway account. CipherLock will alert " +
                    "you (and log a breach event) the first time anyone fills this credential."
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })


        val btnPickApp = Button(ctx).apply {
            text = if (selectedPackageName.isEmpty()) "📱 Pick App" else "📱 Change App"
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1976D2"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(4) }
        }

        // App row: selected name + pick button side by side
        val appRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        tvSelectedApp.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        appRow.addView(tvSelectedApp)
        appRow.addView(btnPickApp)
        container.addView(appRow)

        container.addView(TextView(ctx).apply {
            text = "💡 CHIPHERLOCK will offer this password when you open that app"
            textSize = 11f; setTextColor(Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        })

        // Pick app tapped
        btnPickApp.setOnClickListener {
            showAppPicker { appName, packageName ->
                selectedPackageName      = packageName
                tvSelectedApp.text       = appName
                btnPickApp.text          = "📱 Change App"
            }
        }

        // Pre-fill for edit
        existing?.let {
            etService.setText(it.service)
            etUsername.setText(it.username)
            etPassword.setText(String(it.password))   // EditText accepts CharSequence only
        }

        // Live strength
        fun refreshStrength(pw: String) {
            val r = PasswordStrengthAnalyzer.analyze(pw)
            val c = Color.parseColor(r.strength.colorHex)
            progressBar.progress = r.score
            progressBar.progressTintList = ColorStateList.valueOf(c)
            tvStrengthLabel.text = r.strength.label; tvStrengthLabel.setTextColor(c)
            tvPositives.text   = if (r.positives.isEmpty())   "" else "✅ " + r.positives.joinToString("\n✅ ")
            tvSuggestions.text = if (r.suggestions.isEmpty()) "" else "💡 " + r.suggestions.joinToString("\n💡 ")
        }
        etPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshStrength(s.toString()) }
        })
        existing?.let { refreshStrength(String(it.password)) }

        btnGenerate.setOnClickListener {
            val generated = PasswordGenerator.generate(PasswordGenerator.Options())
            if (generated != null) {
                etPassword.setText(generated)
                etPassword.setSelection(generated.length)
                pwVisible = true
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnEye.setImageResource(R.drawable.ic_visibility_off)
                Toast.makeText(ctx, "Strong password generated!", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle(if (isEdit) "Edit Password" else "Add Password")
            .setView(scroll)
            .setPositiveButton(if (isEdit) "Save" else "Add", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val service  = etService.text.toString().trim()
                    val editable = etPassword.text
                    val pwChars  = CharArray(editable.length).also { editable.getChars(0, editable.length, it, 0) }
                    if (service.isEmpty() || pwChars.isEmpty()) {
                        Toast.makeText(ctx, "Service name and password are required", Toast.LENGTH_SHORT).show()
                        java.util.Arrays.fill(pwChars, '\u0000')
                        return@setOnClickListener
                    }
                    val entry = PasswordEntry(
                        id          = existing?.id ?: UUID.randomUUID().toString(),
                        service     = service,
                        username    = etUsername.text.toString().trim(),
                        password    = pwChars,
                        packageName = selectedPackageName,
                        timestamp   = existing?.timestamp ?: System.currentTimeMillis(),
                        isCanary    = swCanary.isChecked          // Layer 2
                    )
                    if (isEdit) updateEntry(entry) else addEntry(entry)
                    editable.clear()
                    dialog.dismiss()
                }

            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quick Generate — long press FAB
    // ─────────────────────────────────────────────────────────────────────────
    private fun showQuickGenerateDialog() {
        val ctx = requireContext()
        val d   = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }

        val tvGenerated = TextView(ctx).apply {
            textSize = 15f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#212121"))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = "Generating..."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        container.addView(tvGenerated)

        val strengthRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0E0E0"))
        }
        val tvStrength = TextView(ctx).apply {
            textSize = 12f; setTypeface(null, Typeface.BOLD); minWidth = dp(80); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }
        }
        strengthRow.addView(progressBar); strengthRow.addView(tvStrength)
        container.addView(strengthRow)

        fun label(text: String) = TextView(ctx).apply {
            this.text = text; textSize = 12f; setTextColor(Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        fun toggle(label: String, checked: Boolean) = CheckBox(ctx).apply { text = label; isChecked = checked; textSize = 13f }

        container.addView(label("Password Length"))
        val tvLengthValue = TextView(ctx).apply {
            text = "16 characters"; textSize = 13f; setTypeface(null, Typeface.BOLD); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val seekLength = SeekBar(ctx).apply { min = 8; max = 32; progress = 16 }
        container.addView(tvLengthValue); container.addView(seekLength)

        container.addView(label("Include"))
        val cbUpper   = toggle("Uppercase letters (A–Z)", true)
        val cbLower   = toggle("Lowercase letters (a–z)", true)
        val cbDigits  = toggle("Numbers (0–9)", true)
        val cbSymbols = toggle("Symbols (!@#\$...)", true)
        val cbNoAmb   = toggle("Avoid ambiguous characters (l, 1, O, 0)", true)
        listOf(cbUpper, cbLower, cbDigits, cbSymbols, cbNoAmb).forEach { container.addView(it) }

        var currentPassword = ""

        fun regenerate() {
            val opts = PasswordGenerator.Options(
                length         = seekLength.progress,
                useUppercase   = cbUpper.isChecked,
                useLowercase   = cbLower.isChecked,
                useDigits      = cbDigits.isChecked,
                useSymbols     = cbSymbols.isChecked,
                avoidAmbiguous = cbNoAmb.isChecked
            )
            val pw = PasswordGenerator.generate(opts) ?: return
            currentPassword      = pw
            tvGenerated.text     = pw
            val r = PasswordStrengthAnalyzer.analyze(pw)
            val c = Color.parseColor(r.strength.colorHex)
            progressBar.progress = r.score
            progressBar.progressTintList = ColorStateList.valueOf(c)
            tvStrength.text = r.strength.label; tvStrength.setTextColor(c)
        }

        regenerate()
        listOf(cbUpper, cbLower, cbDigits, cbSymbols, cbNoAmb).forEach { it.setOnCheckedChangeListener { _, _ -> regenerate() } }
        seekLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                tvLengthValue.text = "$v characters"
                if (fromUser) regenerate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(ctx)
            .setTitle("🎲 Generate Password")
            .setView(ScrollView(ctx).also { it.addView(container) })
            .setPositiveButton("Copy & Save to Vault", null)
            .setNeutralButton("🔄 Regenerate") { _, _ -> }
            .setNegativeButton("Copy Only") { _, _ ->
                if (currentPassword.isNotEmpty()) {
                    copyToClipboard(text = currentPassword.toCharArray(), showToast = false)
                    Toast.makeText(ctx, "✅ Copied! Go paste it in the other app.", Toast.LENGTH_LONG).show()
                }
            }
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { regenerate() }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (currentPassword.isEmpty()) return@setOnClickListener
                    copyToClipboard(text = currentPassword.toCharArray(), showToast = false)
                    val nameInput = EditText(ctx).apply {
                        hint = "e.g. Gmail, BDO, Facebook"
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("Save to Vault")
                        .setMessage("What service is this password for?")
                        .setView(nameInput)
                        .setPositiveButton("Save") { _, _ ->
                            val service = nameInput.text.toString().trim()
                            if (service.isEmpty()) { Toast.makeText(ctx, "Enter a service name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                            addEntry(PasswordEntry(service = service, username = "", password = currentPassword.toCharArray()))
                            Toast.makeText(ctx, "✅ Saved! Go paste it in the other app.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                        .setNegativeButton("Skip — just copy") { _, _ ->
                            Toast.makeText(ctx, "✅ Copied! Go paste it.", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                        .show()
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────
    private fun addEntry(entry: PasswordEntry) {
        entries.add(0, entry); saveEntries()
        adapter.notifyItemInserted(0); recyclerView.scrollToPosition(0); refreshEmpty()
    }

    private fun updateEntry(entry: PasswordEntry) {
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) { entries[idx] = entry; saveEntries(); adapter.notifyItemChanged(idx) }
    }

    private fun confirmDelete(entry: PasswordEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete entry?")
            .setMessage("Remove '${entry.service}' from your vault?")
            .setPositiveButton("Delete") { _, _ ->
                val idx = entries.indexOfFirst { it.id == entry.id }
                if (idx >= 0) { entries.removeAt(idx); saveEntries(); adapter.notifyItemRemoved(idx); refreshEmpty() }
            }
            .setNegativeButton("Cancel", null).show()
    }




    private fun saveEntries() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("service", e.service)
                put("username", e.username)
                put("password", String(e.password))
                put("packageName", e.packageName)
                put("timestamp", e.timestamp)
                put("isCanary", e.isCanary)
            })
        }
        val ctx = context ?: return   // Fragment detached — skip save silently
        try {
            VaultKeyManager.saveEntries(arr.toString(), ctx)
            // Notify report tab that vault data changed
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(ctx)
                .sendBroadcast(android.content.Intent("com.chipherlock.VAULT_DATA_CHANGED"))
        } catch (_: IllegalStateException) {
            Toast.makeText(ctx,
                "Vault locked — re-authenticate to save", Toast.LENGTH_LONG).show()
        }
    }


    private fun loadEntries() {
        entries.forEach { it.wipe() }
        entries.clear()

        showRemoteLockBanner()

        val json = try {
            VaultKeyManager.loadEntries(requireContext()) ?: "[]"
        } catch (_: IllegalStateException) {
            Toast.makeText(requireContext(),
                "Vault locked — please re-authenticate", Toast.LENGTH_LONG).show()
            adapter.notifyDataSetChanged()
            return
        } catch (e: javax.crypto.AEADBadTagException) {
            if (com.chipherlock.app.BuildConfig.DEBUG) android.util.Log.e("VaultFragment", "GCM auth tag failure", e)
            Toast.makeText(requireContext(),
                "⚠️ Vault integrity check failed.", Toast.LENGTH_LONG).show()
            adapter.notifyDataSetChanged()
            return
        }

        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            entries.add(PasswordEntry(
                id          = o.optString("id", UUID.randomUUID().toString()),
                service     = o.getString("service"),
                username    = o.optString("username", ""),
                password    = o.getString("password").toCharArray(),
                packageName = o.optString("packageName", ""),
                timestamp   = o.optLong("timestamp", System.currentTimeMillis()),
                isCanary    = o.optBoolean("isCanary", false)
            ))
        }
        adapter.notifyDataSetChanged()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun copyToClipboard(text: CharArray, showToast: Boolean = true) {


        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(CLIP_TAG, String(text))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val extras = android.os.PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)

        if (showToast) {
            Toast.makeText(ctx, "Password copied — clears in 30 seconds", Toast.LENGTH_SHORT).show()
        }

        pendingClipboardWipe?.let { clipboardWipeHandler.removeCallbacks(it) }

        val wipe = Runnable {
            val current = cm.primaryClip
            val isOurs = current != null && (0 until current.itemCount).any { i ->
                current.description.label == CLIP_TAG
            }
            if (isOurs) {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
            pendingClipboardWipe = null
        }
        pendingClipboardWipe = wipe
        clipboardWipeHandler.postDelayed(wipe, CLIPBOARD_WIPE_DELAY_MS)
    }

    private fun refreshEmpty() {
        tvEmpty.visibility      = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE   else View.VISIBLE
    }
    private fun showRemoteLockBanner() {
        val ctx = requireContext()
        if (!RevocationCheck.isRemotelyLocked(ctx)) return
        com.google.android.material.snackbar.Snackbar.make(
            recyclerView,
            "⚠️ This device is marked as lost. Use master password to recover.",
            com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
        ).setAction("I have my phone") {
            VaultAuthManager.prompt(
                activity  = requireActivity() as androidx.fragment.app.FragmentActivity,
                title     = "Confirm identity",
                subtitle  = "Authenticate to clear the remote lock",
                onSuccess = {
                    RevocationCheck.clearRemoteLock(ctx)
                    Toast.makeText(ctx, "Remote lock cleared.", Toast.LENGTH_SHORT).show()
                }
            )
        }.show()
    }
    companion object {
        private const val CLIP_TAG = "chipherlock_pw"
        private const val CLIPBOARD_WIPE_DELAY_MS = 30_000L
        fun newInstance() = VaultFragment()

        // loadEntriesStatic is used by ChipherLockAutofillService.
        // It now checks VaultKeyManager instead of creating EncryptedSharedPreferences.
        // If the vault is locked the autofill service returns no suggestions — correct behaviour.
        fun loadEntriesStatic(context: Context): List<PasswordEntry> {
            if (!VaultKeyManager.isUnlocked()) return emptyList()
            return try {
                val json = VaultKeyManager.loadEntries(context) ?: return emptyList()
                val arr  = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    PasswordEntry(
                        id          = o.optString("id", UUID.randomUUID().toString()),
                        service     = o.getString("service"),
                        username    = o.optString("username", ""),
                        password    = o.getString("password").toCharArray(),
                        packageName = o.optString("packageName", ""),
                        timestamp   = o.optLong("timestamp", System.currentTimeMillis()),
                        isCanary    = o.optBoolean("isCanary", false)
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter — fully programmatic
// ─────────────────────────────────────────────────────────────────────────────
class VaultAdapter(
    private val entries: List<PasswordEntry>,
    private val onCopy:   (PasswordEntry) -> Unit,
    private val onDelete: (PasswordEntry) -> Unit,
    private val onEdit:   (PasswordEntry) -> Unit
) : RecyclerView.Adapter<VaultAdapter.VH>() {

    private val revealed = mutableSetOf<String>()
    private val dateFmt  = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    inner class VH(val card: androidx.cardview.widget.CardView) : RecyclerView.ViewHolder(card) {
        lateinit var tvAvatar   : TextView
        lateinit var tvService  : TextView
        lateinit var tvUser     : TextView
        lateinit var tvPass     : TextView
        lateinit var tvDate     : TextView
        lateinit var tvBadge    : TextView
        lateinit var barStrength: ProgressBar
        lateinit var btnReveal  : ImageButton
        lateinit var btnCopy    : ImageButton
        lateinit var btnEdit    : ImageButton
        lateinit var btnDelete  : ImageButton
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d   = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = androidx.cardview.widget.CardView(ctx).apply {
            radius = dp(12).toFloat(); cardElevation = dp(2).toFloat()
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(6); bottomMargin = dp(6) }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(row)

        val tvAvatar = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            gravity = Gravity.CENTER; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1976D2")) }
        }
        row.addView(tvAvatar)

        val textBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tvService = TextView(ctx).apply {
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#212121"))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvBadge = TextView(ctx).apply {
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        }
        topRow.addView(tvService); topRow.addView(tvBadge); textBlock.addView(topRow)

        val tvUser = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#757575")); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        }
        textBlock.addView(tvUser)

        val tvPass = TextView(ctx).apply {
            textSize = 13f; setTextColor(Color.parseColor("#424242")); typeface = Typeface.MONOSPACE
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        textBlock.addView(tvPass)

        val barStrength = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) }
        }
        textBlock.addView(barStrength)

        val tvDate = TextView(ctx).apply {
            textSize = 10f; setTextColor(Color.parseColor("#BDBDBD"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        textBlock.addView(tvDate)
        row.addView(textBlock)

        val btnCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        }
        fun iconBtn(icon: Int, tint: String) = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setImageResource(icon); imageTintList = ColorStateList.valueOf(Color.parseColor(tint)); background = null
        }
        val btnReveal = iconBtn(R.drawable.ic_visibility, "#757575")
        val btnCopy   = iconBtn(R.drawable.ic_copy,       "#1976D2")
        val btnEdit   = iconBtn(R.drawable.ic_edit,       "#757575")
        val btnDelete = iconBtn(R.drawable.ic_delete,     "#F44336")
        listOf(btnReveal, btnCopy, btnEdit, btnDelete).forEach { btnCol.addView(it) }
        row.addView(btnCol)

        return VH(card).also {
            it.tvAvatar = tvAvatar; it.tvService = tvService; it.tvUser = tvUser
            it.tvPass = tvPass; it.tvDate = tvDate; it.tvBadge = tvBadge
            it.barStrength = barStrength
            it.btnReveal = btnReveal; it.btnCopy = btnCopy; it.btnEdit = btnEdit; it.btnDelete = btnDelete
        }
    }

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry   = entries[position]
        val result  = PasswordStrengthAnalyzer.analyze(String(entry.password))
        val color   = Color.parseColor(result.strength.colorHex)
        val isShown = entry.id in revealed

        holder.tvAvatar.text = entry.service.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        (holder.tvAvatar.background as GradientDrawable).setColor(color)
        holder.tvService.text = entry.service
        holder.tvUser.text    = entry.username.ifEmpty { "No username saved" }
        holder.tvService.text = if (entry.isCanary) "🪤 ${entry.service}" else entry.service
        holder.tvPass.text    = if (isShown) String(entry.password) else "••••••••••••"

        holder.tvDate.text    = dateFmt.format(Date(entry.timestamp))
        holder.tvBadge.text   = result.strength.label; holder.tvBadge.setTextColor(color)
        holder.tvBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 24f
            setStroke(2, color); setColor(Color.TRANSPARENT)
        }
        holder.barStrength.progress = result.score
        holder.barStrength.progressTintList = ColorStateList.valueOf(color)
        holder.btnReveal.setImageResource(if (isShown) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        holder.btnReveal.setOnClickListener {
            if (isShown) revealed.remove(entry.id) else revealed.add(entry.id)
            notifyItemChanged(position)
        }
        holder.btnCopy.setOnClickListener   { onCopy(entry) }
        holder.btnEdit.setOnClickListener   { onEdit(entry) }
        holder.btnDelete.setOnClickListener { onDelete(entry) }
    }
}