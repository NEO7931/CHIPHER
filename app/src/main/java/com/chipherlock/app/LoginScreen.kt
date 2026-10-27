package com.chipherlock.app

import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.chipherlock.app.databinding.LayoutLoginBinding
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable

/** Full-screen Master Key page shown on app open and whenever the app returns from the background. */
class LoginScreen(
    private val activity: AppCompatActivity,
    private val b: LayoutLoginBinding,
    private val onUnlocked: () -> Unit
) {
    /** True while a system credential screen is in front of the app; the session must not re-lock then. */
    var authInProgress = false
        private set

    val isShowing: Boolean get() = b.root.visibility == View.VISIBLE

    private val handler = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density
    private var createMode = false
    private var busy = false
    private var cooldownActive = false

    private val cooldownTick = Runnable { refreshCooldown() }

    private val spinner by lazy {
        val spec = CircularProgressIndicatorSpec(
            activity, null, 0,
            com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall
        )
        spec.indicatorColors = intArrayOf(CipherPalette.onPrimary)
        IndeterminateDrawable.createCircularDrawable(activity, spec)
    }

    init {
        b.btnLoginSubmit.setOnClickListener { submit() }
        b.btnLoginBiometric.setOnClickListener { unlockWithBiometric() }
        b.btnLoginTerms.setOnClickListener {
            showLegal(R.string.login_terms, R.string.terms_placeholder)
        }
        b.btnLoginPrivacy.setOnClickListener {
            showLegal(R.string.login_privacy, R.string.privacy_placeholder)
        }
        b.etLoginKey.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_DONE -> { submit(); true }
                EditorInfo.IME_ACTION_NEXT -> { b.etLoginConfirm.requestFocus(); true }
                else -> false
            }
        }
        b.etLoginConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        b.etLoginKey.doAfterTextChanged { if (!cooldownActive) clearError() }
        b.etLoginConfirm.doAfterTextChanged { if (!cooldownActive) clearError() }
    }

    fun show(animate: Boolean) {
        createMode = !VaultKeyManager.isEnrolled(activity)
        b.tvLoginTitle.setText(if (createMode) R.string.login_title_create else R.string.login_title_unlock)
        b.tvLoginSubtitle.setText(if (createMode) R.string.login_subtitle_create else R.string.login_subtitle_unlock)
        b.tilLoginConfirm.visibility = if (createMode) View.VISIBLE else View.GONE
        b.etLoginKey.imeOptions = if (createMode) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        b.btnLoginBiometric.visibility =
            if (!createMode && VaultKeyManager.isBioAvailable(activity) && VaultAuthManager.canAuthenticate(activity))
                View.VISIBLE else View.GONE

        b.etLoginKey.text?.clear()
        b.etLoginConfirm.text?.clear()
        clearError()
        setBusy(false)
        b.loginScroll.scrollTo(0, 0)

        b.root.animate().cancel()
        b.root.alpha = 1f
        b.root.visibility = View.VISIBLE
        if (animate) {
            b.loginContent.alpha = 0f
            b.loginContent.translationY = 16 * density
            b.loginContent.animate()
                .alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            b.loginContent.alpha = 1f
            b.loginContent.translationY = 0f
        }
        refreshCooldown()
    }

    fun focusInput() {
        if (!isShowing || busy || cooldownActive) return
        b.etLoginKey.requestFocus()
        b.etLoginKey.postDelayed({
            if (isShowing && b.etLoginKey.hasFocus()) {
                WindowCompat.getInsetsController(activity.window, b.etLoginKey)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }, 200)
    }

    private fun dismiss() {
        handler.removeCallbacks(cooldownTick)
        WindowCompat.getInsetsController(activity.window, b.root).hide(WindowInsetsCompat.Type.ime())
        b.root.clearFocus()
        onUnlocked()
        b.loginContent.animate()
            .translationY(-12 * density)
            .setDuration(220)
            .start()
        b.root.animate()
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                b.root.visibility = View.GONE
                b.root.alpha = 1f
                b.loginContent.translationY = 0f
            }
            .start()
    }

    private fun showLegal(titleRes: Int, bodyRes: Int) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_legal, null)
        content.findViewById<android.widget.TextView>(R.id.tvLegalBody).setText(bodyRes)
        VaultDialogUi.builder(activity)
            .setTitle(titleRes)
            .setView(content)
            .setPositiveButton(R.string.legal_close, null)
            .create()
            .also { VaultDialogUi.showStyled(it) }
    }

    private fun submit() {
        if (busy) return
        if (LockoutCounter.cooldownRemainingMs(activity) > 0) { refreshCooldown(); return }
        if (createMode) createMasterKey() else unlockWithPassword()
    }

    private fun unlockWithPassword() {
        if (b.etLoginKey.text.isNullOrEmpty()) {
            showError(activity.getString(R.string.login_error_empty)); shake(); return
        }
        val pwChars = b.etLoginKey.text?.toString()?.toCharArray() ?: charArrayOf()
        b.etLoginKey.text?.clear()
        setBusy(true)

        Thread {
            val ok = VaultKeyManager.unlockWithPassword(pwChars, activity)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                setBusy(false)
                if (ok) {
                    VaultAuthManager.markUnlocked()
                    LockoutCounter.reset(activity)
                    dismiss()
                } else {
                    LockoutCounter.recordFailure(activity)
                    showError(activity.getString(R.string.login_error_wrong))
                    shake()
                    refreshCooldown()
                    focusInput()
                }
            }
        }.start()
    }

    private fun createMasterKey() {
        val pw1 = b.etLoginKey.text?.toString().orEmpty()
        val pw2 = b.etLoginConfirm.text?.toString().orEmpty()
        if (pw1.length < 8) {
            showError(activity.getString(R.string.login_error_short)); shake(); return
        }
        if (pw1 != pw2) {
            showError(activity.getString(R.string.login_error_mismatch)); shake(); return
        }
        val pwChars = pw1.toCharArray()
        b.etLoginKey.text?.clear()
        b.etLoginConfirm.text?.clear()
        setBusy(true)

        Thread {
            try {
                VaultKeyManager.enroll(pwChars, activity)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    setBusy(false)
                    VaultAuthManager.markUnlocked()
                    dismiss()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    setBusy(false)
                    showError(activity.getString(R.string.login_error_setup, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun unlockWithBiometric() {
        if (busy) return
        if (LockoutCounter.cooldownRemainingMs(activity) > 0) { refreshCooldown(); return }
        val cipher = try {
            VaultKeyManager.getBioDecryptCipher(activity)
        } catch (_: Exception) {
            b.btnLoginBiometric.visibility = View.GONE
            showError(activity.getString(R.string.login_error_biometric))
            focusInput()
            return
        }

        RevocationCheck.checkInBackground(activity)
        authInProgress = true
        VaultAuthManager.promptWithCryptoObject(
            activity     = activity,
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            onSuccess    = { result ->
                authInProgress = false
                val authCipher = result.cryptoObject?.cipher
                if (authCipher != null && VaultKeyManager.unlockWithBiometric(authCipher, activity)) {
                    dismiss()
                } else {
                    showError(activity.getString(R.string.login_error_biometric))
                    focusInput()
                }
            },
            onFallback = {
                authInProgress = false
                focusInput()
            },
            onError = { code, msg ->
                authInProgress = false
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_CANCELED) {
                    showError(msg.toString())
                }
                refreshCooldown()
            }
        )
    }

    private fun refreshCooldown() {
        handler.removeCallbacks(cooldownTick)
        val ms = LockoutCounter.cooldownRemainingMs(activity)
        if (ms > 0) {
            cooldownActive = true
            val seconds = ((ms + 999) / 1000).toInt()
            showError(activity.getString(R.string.login_error_cooldown, seconds))
            b.btnLoginSubmit.isEnabled = false
            b.btnLoginBiometric.isEnabled = false
            if (isShowing) handler.postDelayed(cooldownTick, 1000)
        } else if (cooldownActive) {
            cooldownActive = false
            clearError()
            b.btnLoginSubmit.isEnabled = true
            b.btnLoginBiometric.isEnabled = true
        }
    }

    private fun setBusy(isBusy: Boolean) {
        busy = isBusy
        b.etLoginKey.isEnabled = !isBusy
        b.etLoginConfirm.isEnabled = !isBusy
        b.btnLoginBiometric.isEnabled = !isBusy
        b.btnLoginSubmit.icon = if (isBusy) spinner else null
        b.btnLoginSubmit.setText(
            when {
                isBusy && createMode -> R.string.login_creating
                isBusy -> R.string.login_unlocking
                createMode -> R.string.login_create
                else -> R.string.login_unlock
            }
        )
    }

    private fun showError(message: String) {
        b.tvLoginError.text = message
        b.tvLoginError.visibility = View.VISIBLE
    }

    private fun clearError() {
        b.tvLoginError.visibility = View.GONE
    }

    private fun shake() {
        val d = 8 * density
        ObjectAnimator.ofFloat(b.tilLoginKey, View.TRANSLATION_X, 0f, d, -d, d * 0.6f, -d * 0.6f, 0f)
            .setDuration(300)
            .start()
    }
}
