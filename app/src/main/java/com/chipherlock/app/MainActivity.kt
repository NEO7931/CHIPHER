package com.chipherlock.app

import androidx.biometric.BiometricPrompt
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

    // ─── REM-005 — inactivity timer ──────────────────────────────────────
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRunnable = Runnable {
        VaultAuthManager.markLocked()
        if (binding.bottomNavigation.selectedItemId == R.id.nav_vault) {
            binding.bottomNavigation.selectedItemId = R.id.nav_sms
            android.widget.Toast.makeText(
                this,
                "Vault locked after inactivity. Tap Vault to re-authenticate.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    private val idleTimeoutMs = 3 * 60 * 1000L
    private var startupSound: android.media.MediaPlayer? = null

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, idleTimeoutMs)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

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
                .setTitle("⚠️ Security Warning")
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
        resetIdleTimer()   // REM-005 — start inactivity countdown
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
            .setTitle("🛠️  Previous crash log")
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
            score >= 70 -> "🔴 HIGH RISK ($score/100)"
            score >= 40 -> "🟠 MEDIUM RISK ($score/100)"
            else        -> "🟡 LOW RISK ($score/100)"
        }
        val sb = StringBuilder()
        sb.append("🚨 ALERT FROM NOTIFICATION\n\n")
        sb.append("⚠️ [$category]\n")
        sb.append("$riskLabel\n")
        sb.append("From: $sender\n")
        if (keywords.isNotEmpty()) sb.append("Keywords: $keywords\n")
        if (phFlags.isNotEmpty())  sb.append("PH Flags: $phFlags\n")
        if (hasUrl)                sb.append("⚠️ Suspicious URL detected\n")
        if (suspSender)            sb.append("⚠️ Suspicious sender number\n")
        sb.append("─────────────────────\n")
        sb.append("Message:\n\"$body\"")
        return sb.toString()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            // REM-002 — vault + report tabs are gated; everything else navigates immediately.
            if ((item.itemId == R.id.nav_vault || item.itemId == R.id.nav_report)
                && VaultAuthManager.shouldGate()) {
                gateVaultThen {
                    showFragment(
                        if (item.itemId == R.id.nav_report) ReportFragment()
                        else VaultFragment()
                    )
                    binding.bottomNavigation.selectedItemId = item.itemId
                }
                return@setOnItemSelectedListener false
            }
            val fragment = when (item.itemId) {
                R.id.nav_sms     -> SmsFragment.newInstance(null)
                R.id.nav_vault   -> VaultFragment()
                R.id.nav_network -> NetworkFragment()
                R.id.nav_coach   -> HelpDeskFragment()
                R.id.nav_report  -> ReportFragment()
                else             -> SmsFragment.newInstance(null)
            }
            showFragment(fragment)
            true
        }
    }
    private fun showEnrollmentDialog(onSuccess: () -> Unit) {
        val ctx = this
        val d   = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        container.addView(android.widget.TextView(ctx).apply {
            text = "Choose a master password. You will need this to recover your vault if you " +
                    "lose your phone or change devices. It cannot be reset."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#424242"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        })

        fun makeField(hint: String): android.widget.EditText {
            val label = android.widget.TextView(ctx).apply {
                text = hint; textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#757575"))
            }
            val field = android.widget.EditText(ctx).apply {
                this.hint = hint
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            container.addView(label)
            container.addView(field)
            return field
        }

        val etPw  = makeField("Master password")
        val etCon = makeField("Confirm master password")

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🔐 Create vault")
            .setView(android.widget.ScrollView(ctx).also { it.addView(container) })
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        val pw1 = etPw.text.toString()
                        val pw2 = etCon.text.toString()
                        if (pw1.length < 8) {
                            android.widget.Toast.makeText(ctx,
                                "Password must be at least 8 characters",
                                android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (pw1 != pw2) {
                            android.widget.Toast.makeText(ctx,
                                "Passwords do not match",
                                android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val pwChars = pw1.toCharArray()
                        etPw.text.clear(); etCon.text.clear()

                        val btn = dialog.getButton(
                            androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        btn.isEnabled = false

                        Thread {
                            try {
                                VaultKeyManager.enroll(pwChars, ctx)
                                runOnUiThread {
                                    dialog.dismiss()
                                    binding.bottomNavigation.selectedItemId = R.id.nav_vault
                                    onSuccess()
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    btn.isEnabled = true
                                    android.widget.Toast.makeText(ctx,
                                        "Setup failed: ${e.message}",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
            }
    }
    private fun showPasswordUnlockDialog(onSuccess: () -> Unit) {
        val ctx = this
        val d   = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val etPw = android.widget.EditText(ctx).apply {
            hint = "Master password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🔑 Enter master password")
            .setView(etPw)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        val pwChars = etPw.text.toString().toCharArray()
                        etPw.text.clear()

                        val btn = dialog.getButton(
                            androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        btn.isEnabled = false

                        Thread {
                            val ok = VaultKeyManager.unlockWithPassword(pwChars, ctx)
                            runOnUiThread {
                                if (ok) {
                                    VaultAuthManager.markUnlocked()
                                    LockoutCounter.reset(ctx)
                                    dialog.dismiss()
                                    binding.bottomNavigation.selectedItemId = R.id.nav_vault
                                    onSuccess()
                                } else {
                                    btn.isEnabled = true
                                    LockoutCounter.recordFailure(ctx)
                                    android.widget.Toast.makeText(ctx,
                                        "Wrong password.",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
            }
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun gateVaultThen(onSuccess: () -> Unit) {
        val cooldownMs = LockoutCounter.cooldownRemainingMs(this)
        if (cooldownMs > 0) {
            android.widget.Toast.makeText(this,
                "Too many attempts. Try again in ${cooldownMs / 1000}s.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (!VaultKeyManager.isEnrolled(this)) {
            showEnrollmentDialog(onSuccess)
            return
        }

        if (!VaultAuthManager.canAuthenticate(this)) {
            showPasswordUnlockDialog(onSuccess)
            return
        }

        val cipher = try {
            VaultKeyManager.getBioDecryptCipher(this)
        } catch (_: Exception) {
            showPasswordUnlockDialog(onSuccess)
            return
        }

        RevocationCheck.checkInBackground(this)

        VaultAuthManager.promptWithCryptoObject(
            activity     = this,
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            onSuccess    = { result ->
                val authCipher = result.cryptoObject?.cipher
                if (authCipher != null && VaultKeyManager.unlockWithBiometric(authCipher, this)) {
                    binding.bottomNavigation.selectedItemId = R.id.nav_vault
                    onSuccess()
                } else {
                    showPasswordUnlockDialog(onSuccess)
                }
            },
            onFallback = { showPasswordUnlockDialog(onSuccess) },
            onError    = { code, msg ->
                android.widget.Toast.makeText(this,
                    "Auth error ($code): $msg",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        )
    }



    override fun onResume() {
        super.onResume()
        resetIdleTimer()   // REM-005
        val onVault = binding.bottomNavigation.selectedItemId == R.id.nav_vault
        if (onVault && VaultAuthManager.shouldGate()) {
            // Don't auto-prompt on resume — vault gate fires only when user
            // explicitly taps the Vault tab. Just land on SMS quietly.
            showFragment(SmsFragment.newInstance(null))
            binding.bottomNavigation.selectedItemId = R.id.nav_sms
        }
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleRunnable)   // REM-005 — don't burn timer in bg
        VaultAuthManager.markLocked()
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