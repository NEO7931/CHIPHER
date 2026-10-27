package com.chipherlock.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chipherlock.app.databinding.ActivityMainBinding
import com.chipherlock.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSIONS_REQUEST_CODE = 100

    private lateinit var loginScreen: LoginScreen
    private var startupSound: android.media.MediaPlayer? = null

    private val REQUIRED_PERMISSIONS = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Startup sound — plays on media stream, respects silent mode
        try {
            startupSound = android.media.MediaPlayer.create(this, R.raw.startup_sound)
            startupSound?.setOnCompletionListener { it.release(); startupSound = null }
            startupSound?.start()
        } catch (_: Exception) { }

        // REM-005 — block screenshots, screen recording, and Recent Apps preview.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // Phase 6 — root/tamper detection via RootBeer.
        // Warns the user if the device is rooted; does not block access because
        // legitimate power users may run rooted devices. The warning ensures
        // informed consent about the elevated risk to vault security.
        val rootBeer = com.scottyab.rootbeer.RootBeer(this)
        if (rootBeer.isRooted) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Security Warning")
                .setMessage(
                    "This device appears to be rooted or tampered with.\n\n" +
                            "Rooted devices have reduced security guarantees. " +
                            "Your vault data may be accessible to other apps with root access.\n\n" +
                            "Proceed with caution."
                )
                .setPositiveButton("I Understand") { d, _ -> d.dismiss() }
                .setCancelable(false)
                .show()
        }

        // REM-006 — debug: custom crash handler. Release: Firebase Crashlytics.
        if (BuildConfig.DEBUG) {
            installCrashHandler()
        } else {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setCrashlyticsCollectionEnabled(true)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // REM-006 — debug: show local crash log. Release: Crashlytics handles it.
        if (BuildConfig.DEBUG) {
            showPreviousCrashIfAny()
        } else {
            // Clean up any leftover debug crash file from a previous debug build.
            File(filesDir, "last_crash.txt").takeIf { it.exists() }?.delete()
            // Log app start event to Crashlytics for session tracking.
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .log("App started — release build")
        }

        requestAppPermissions()
        setupBottomNavigation()
        handleIncomingAlert(savedInstanceState)

        loginScreen = LoginScreen(this, binding.loginOverlay) { onSessionUnlocked() }
        if (VaultAuthManager.shouldGate()) loginScreen.show(animate = true)
    }


    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(filesDir, "last_crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                crashFile.writeText(
                    "═══════════════════════════════════════════════\n" +
                            "CHIPHERLOCK crash at $ts\n" +
                            "Thread: ${thread.name}\n" +
                            "═══════════════════════════════════════════════\n\n" +
                            sw.toString()
                )
            } catch (_: Throwable) { /* writing failed — give up gracefully */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showPreviousCrashIfAny() {
        val crashFile = File(filesDir, "last_crash.txt")
        if (!crashFile.exists()) return
        val text = try { crashFile.readText() } catch (_: Throwable) { return }
        if (text.isBlank()) return

        // Show in a dismissable dialog so the user can read it and screenshot it.
        // The dialog is intentionally selectable text so it can be copied too.
        val tv = android.widget.TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(40, 30, 40, 30)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }

        android.app.AlertDialog.Builder(this)
            .setTitle("Previous crash log")
            .setView(scroll)
            .setPositiveButton("Dismiss") { d, _ -> d.dismiss() }
            .setNegativeButton("Clear log") { _, _ -> crashFile.delete() }
            .show()
    }

    private fun handleIncomingAlert(savedInstanceState: Bundle?) {
        val openTab = intent.getStringExtra("open_tab")

        if (openTab == "sms") {
            val sender     = intent.getStringExtra("alert_sender") ?: "Unknown"
            val body       = intent.getStringExtra("alert_body") ?: ""
            val category   = intent.getStringExtra("alert_category") ?: "SUSPICIOUS"
            val score      = intent.getIntExtra("alert_score", 0)
            val keywords   = intent.getStringExtra("alert_keywords") ?: ""
            val phFlags    = intent.getStringExtra("alert_ph_flags") ?: ""
            val hasUrl     = intent.getBooleanExtra("alert_has_url", false)
            val suspSender = intent.getBooleanExtra("alert_suspicious_sender", false)

            val alertText = buildAlertText(
                sender, body, category, score,
                keywords, phFlags, hasUrl, suspSender
            )

            // Save to SharedPreferences so SmsFragment reads it on load
            getSharedPreferences("scam_alert_prefs", MODE_PRIVATE)
                .edit()
                .putString("pending_alert", alertText)
                .apply()

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SmsFragment.newInstance(null))
                .commit()
            binding.bottomNavigation.selectedItemId = R.id.nav_sms

        } else {
            // Always land on SMS — even on system-restore (savedInstanceState != null).
            // Vault gate fires only on explicit tab tap, never on app open.
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SmsFragment.newInstance(null))
                .commit()
            binding.bottomNavigation.selectedItemId = R.id.nav_sms
        }
    }

    private fun buildAlertText(
        sender: String,
        body: String,
        category: String,
        score: Int,
        keywords: String,
        phFlags: String,
        hasUrl: Boolean,
        suspSender: Boolean
    ): String {
        val riskLabel = when {
            score >= 70 -> "HIGH RISK ($score/100)"
            score >= 40 -> "MEDIUM RISK ($score/100)"
            else        -> "LOW RISK ($score/100)"
        }
        val sb = StringBuilder()
        sb.append("ALERT FROM NOTIFICATION\n\n")
        sb.append("[$category]\n")
        sb.append("$riskLabel\n")
        sb.append("From: $sender\n")
        if (keywords.isNotEmpty()) sb.append("Keywords: $keywords\n")
        if (phFlags.isNotEmpty())  sb.append("PH Flags: $phFlags\n")
        if (hasUrl)                sb.append("Suspicious URL detected\n")
        if (suspSender)            sb.append("Suspicious sender number\n")
        sb.append("─────────────────────\n")
        sb.append("Message:\n\"$body\"")
        return sb.toString()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            // REM-002 — vault + report need an unlocked session; the Login page provides it.
            if (isVaultTab(item.itemId) && VaultAuthManager.shouldGate()) {
                loginScreen.show(animate = true)
                loginScreen.focusInput()
                return@setOnItemSelectedListener false
            }
            showFragment(fragmentFor(item.itemId))
            true
        }
    }

    private fun isVaultTab(itemId: Int) = itemId == R.id.nav_vault || itemId == R.id.nav_report

    private fun fragmentFor(itemId: Int): androidx.fragment.app.Fragment = when (itemId) {
        R.id.nav_sms     -> SmsFragment.newInstance(null)
        R.id.nav_vault   -> VaultFragment()
        R.id.nav_network -> NetworkFragment()
        R.id.nav_coach   -> HelpDeskFragment()
        R.id.nav_report  -> ReportFragment()
        else             -> SmsFragment.newInstance(null)
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    // ─── Session lock: Master Key on app open and on return from background ───
    private var vaultTabCleared = false

    private fun onSessionUnlocked() {
        val selected = binding.bottomNavigation.selectedItemId
        if (vaultTabCleared && isVaultTab(selected)) showFragment(fragmentFor(selected))
        vaultTabCleared = false
    }

    private fun lockSession() {
        VaultAuthManager.markLocked()
        if (isVaultTab(binding.bottomNavigation.selectedItemId)) {
            // Drop the decrypted vault UI; it is rebuilt after the next unlock.
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, androidx.fragment.app.Fragment())
                .commitAllowingStateLoss()
            vaultTabCleared = true
        }
        loginScreen.show(animate = false)
    }

    override fun onStart() {
        super.onStart()
        if (::loginScreen.isInitialized && loginScreen.isShowing) loginScreen.focusInput()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !loginScreen.authInProgress) lockSession()
    }

    override fun onPause() {
        super.onPause()
        startupSound?.release()
        startupSound = null
    }

    private fun requestAppPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
