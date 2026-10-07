package com.chipherlock.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

object VaultAuthManager {

    private const val SESSION_WINDOW_MS = 3 * 60 * 1000L

    @Volatile private var lastUnlockMs: Long = 0L

    fun isUnlocked(): Boolean =
        VaultKeyManager.isUnlocked() &&
                (System.currentTimeMillis() - lastUnlockMs < SESSION_WINDOW_MS)

    fun markUnlocked() { lastUnlockMs = System.currentTimeMillis() }

    fun markLocked() {
        lastUnlockMs = 0L
        VaultKeyManager.lockVault()
    }

    fun shouldGate(): Boolean = !isUnlocked()

    fun canAuthenticate(ctx: Context): Boolean {
        val bm   = BiometricManager.from(ctx)
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // ── CryptoObject-bound prompt — use this for vault unlock ────────────────
    //
    // Pass the Cipher from VaultKeyManager.getBioDecryptCipher() as the cryptoObject.
    // After onSuccess fires, call VaultKeyManager.unlockWithBiometric(result.cryptoObject!!.cipher!!, ctx).
    //
    // The hardware only completes the Cipher operation if biometric auth succeeds
    // right now for this specific operation. There is no session flag to bypass.

    fun promptWithCryptoObject(
        activity:     FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject,
        title:        String = "Unlock CipherLock vault",
        subtitle:     String = "Authenticate to view saved passwords",
        onSuccess:    (BiometricPrompt.AuthenticationResult) -> Unit,
        onFallback:   () -> Unit = {},
        onError:      (Int, CharSequence) -> Unit = { _, _ -> }
    ) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                markUnlocked()
                LockoutCounter.reset(activity)
                onSuccess(result)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                LockoutCounter.recordFailure(activity)
                when (errorCode) {
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.ERROR_HW_UNAVAILABLE,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onFallback()
                    else -> onError(errorCode, errString)
                }
            }
            override fun onAuthenticationFailed() {
                LockoutCounter.recordFailure(activity)
            }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(true)
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(info, cryptoObject)
    }

    // ── Plain prompt — use this for confirm-before-action dialogs only ────────
    //
    // Do NOT use this for vault unlock. Use promptWithCryptoObject() instead.

    fun prompt(
        activity:  FragmentActivity,
        title:     String = "Confirm identity",
        subtitle:  String = "Authentication required",
        onSuccess: () -> Unit,
        onError:   (Int, CharSequence) -> Unit = { _, _ -> }
    ) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                LockoutCounter.reset(activity)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                LockoutCounter.recordFailure(activity)
                onError(errorCode, errString)
            }
            override fun onAuthenticationFailed() {
                LockoutCounter.recordFailure(activity)
            }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(info)
    }
}