package com.chipherlock.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VaultKeyManager {

    private const val WRAPS_FILE    = "vault_wraps.json"
    private const val DATA_FILE     = "vault_data.enc"
    private const val KEK_BIO_ALIAS = "chipherlock_kek_bio"
    private const val KEYSTORE_TYPE = "AndroidKeyStore"

    private const val ARGON2_BASE_KB = 64 * 1024
    private const val ARGON2_TIME    = 3
    private const val ARGON2_LANES   = 1
    private const val KEY_BYTES      = 32
    private const val SALT_BYTES     = 32
    private const val GCM_IV_BYTES   = 12
    private const val GCM_TAG_BITS   = 128

    @Volatile private var _dek: SecretKey? = null
    private val dekLock = Any()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun isEnrolled(ctx: Context): Boolean =
        File(ctx.filesDir, WRAPS_FILE).exists()

    fun isUnlocked(): Boolean = _dek != null

    fun isBioAvailable(ctx: Context): Boolean {
        if (!isEnrolled(ctx)) return false
        return try { loadWraps(ctx).optBoolean("bio_available", false) }
        catch (_: Exception) { false }
    }

    fun lockVault() {
        synchronized(dekLock) {
            try {
                (_dek as? SecretKey)?.encoded?.let { Arrays.fill(it, 0) }
            } catch (_: Exception) {}
            _dek = null
        }
    }

    // ── Enroll (first run) ───────────────────────────────────────────────────

    // Call on a background thread — Argon2id is CPU/memory intensive.
    @Throws(Exception::class)
    fun enroll(masterPassword: CharArray, ctx: Context) {
        check(!isEnrolled(ctx)) { "Already enrolled" }

        val rng  = SecureRandom()
        val dek  = generateDek()
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }

        val pmk             = deriveArgon2id(masterPassword, salt, ARGON2_BASE_KB, ARGON2_TIME, ARGON2_LANES)
        val (wpCt, wpIv)    = wrapKey(dek, pmk)
        zeroKey(pmk)

        // Bio key is optional — devices without a configured secure lock screen
        // cannot create user-auth-required keys. Fall back to password-only mode.
        var wbCt: ByteArray? = null
        var wbIv: ByteArray? = null
        try {
            val kekBio = getOrCreateKekBio(ctx)
            val wrap   = wrapKey(dek, kekBio)
            wbCt = wrap.first
            wbIv = wrap.second
        } catch (_: Exception) { /* no secure lock screen — password-only vault */ }

        saveWraps(ctx, salt, wpCt, wpIv, wbCt, wbIv, ARGON2_BASE_KB, ARGON2_TIME, ARGON2_LANES)

        synchronized(dekLock) { _dek = dek }
        Arrays.fill(masterPassword, '\u0000')
    }

    // ── Biometric unlock ─────────────────────────────────────────────────────

    // Returns a Cipher ready to wrap in BiometricPrompt.CryptoObject.
    // The IV stored in vault_wraps.json is loaded so the Cipher is pre-initialised
    // for decryption. The hardware will only complete the operation after a fresh auth.
    @Throws(Exception::class)
    fun getBioDecryptCipher(ctx: Context): Cipher {
        val wraps = loadWraps(ctx)
        val iv    = Base64.decode(wraps.getString("wrap_bio_iv"), Base64.NO_WRAP)
        val ks    = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        val kek   = ks.getKey(KEK_BIO_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("KEK-bio not found — re-enroll")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    // Call with result.cryptoObject!!.cipher!! from BiometricPrompt.AuthenticationResult.
    fun unlockWithBiometric(authenticatedCipher: Cipher, ctx: Context): Boolean {
        return try {
            val wraps    = loadWraps(ctx)
            val wrapped  = Base64.decode(wraps.getString("wrap_bio"), Base64.NO_WRAP)
            val dekBytes = authenticatedCipher.doFinal(wrapped)
            synchronized(dekLock) { _dek = SecretKeySpec(dekBytes, "AES") }
            Arrays.fill(dekBytes, 0)
            true
        } catch (_: Exception) { false }
    }

    // ── Password unlock ──────────────────────────────────────────────────────

    // Call on a background thread — Argon2id is CPU/memory intensive.
    fun unlockWithPassword(password: CharArray, ctx: Context): Boolean {
        return try {
            val wraps    = loadWraps(ctx)
            val salt     = Base64.decode(wraps.getString("salt"),       Base64.NO_WRAP)
            val wpCt     = Base64.decode(wraps.getString("wrap_pw"),    Base64.NO_WRAP)
            val wpIv     = Base64.decode(wraps.getString("wrap_pw_iv"), Base64.NO_WRAP)
            val memKb    = wraps.optInt("argon2_m", ARGON2_BASE_KB)
            val time     = wraps.optInt("argon2_t", ARGON2_TIME)
            val lanes    = wraps.optInt("argon2_p", ARGON2_LANES)

            val pmk      = deriveArgon2id(password, salt, memKb, time, lanes)
            val dekBytes = unwrapKey(wpCt, wpIv, pmk)
            zeroKey(pmk)

            synchronized(dekLock) { _dek = SecretKeySpec(dekBytes, "AES") }
            Arrays.fill(dekBytes, 0)
            Arrays.fill(password, '\u0000')
            true
        } catch (_: Exception) {
            Arrays.fill(password, '\u0000')
            false
        }
    }

    // ── Data operations ──────────────────────────────────────────────────────

    @Throws(IllegalStateException::class)
    fun saveEntries(entriesJson: String, ctx: Context) {
        val dek        = requireDek()
        val iv         = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val plaintext  = entriesJson.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        Arrays.fill(plaintext, 0)
        File(ctx.filesDir, DATA_FILE).writeBytes(iv + ciphertext)
    }

    @Throws(IllegalStateException::class, javax.crypto.AEADBadTagException::class)
    fun loadEntries(ctx: Context): String? {
        val f = File(ctx.filesDir, DATA_FILE)
        if (!f.exists()) return null
        val dek        = requireDek()
        val blob       = f.readBytes()
        if (blob.size <= GCM_IV_BYTES) return null
        val iv         = blob.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val plaintext  = cipher.doFinal(ciphertext)
        val json       = String(plaintext, Charsets.UTF_8)
        Arrays.fill(plaintext, 0)
        return json
    }
    private const val LOAD_HISTORY_FILE = "load_history.enc"

    @Throws(IllegalStateException::class)
    fun saveLoadHistory(historyJson: String, ctx: Context) {
        val dek        = requireDek()
        val iv         = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val plaintext  = historyJson.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        Arrays.fill(plaintext, 0)
        File(ctx.filesDir, LOAD_HISTORY_FILE).writeBytes(iv + ciphertext)
    }

    @Throws(IllegalStateException::class, javax.crypto.AEADBadTagException::class)
    fun loadLoadHistory(ctx: Context): String? {
        val f = File(ctx.filesDir, LOAD_HISTORY_FILE)
        if (!f.exists()) return null
        val dek        = requireDek()
        val blob       = f.readBytes()
        if (blob.size <= GCM_IV_BYTES) return null
        val iv         = blob.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val plaintext  = cipher.doFinal(ciphertext)
        val json       = String(plaintext, Charsets.UTF_8)
        Arrays.fill(plaintext, 0)
        return json
    }

    // ── Change master password ───────────────────────────────────────────────

    // Call on a background thread. DEK is unchanged; only wrap_pw is re-sealed.
    @Throws(Exception::class)
    fun changeMasterPassword(newPassword: CharArray, ctx: Context) {
        val dek           = requireDek()
        val salt          = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val pmk           = deriveArgon2id(newPassword, salt, ARGON2_BASE_KB, ARGON2_TIME, ARGON2_LANES)
        val (wpCt, wpIv)  = wrapKey(dek, pmk)
        zeroKey(pmk)
        Arrays.fill(newPassword, '\u0000')

        val wraps = loadWraps(ctx)
        wraps.put("salt",       Base64.encodeToString(salt,  Base64.NO_WRAP))
        wraps.put("wrap_pw",    Base64.encodeToString(wpCt,  Base64.NO_WRAP))
        wraps.put("wrap_pw_iv", Base64.encodeToString(wpIv,  Base64.NO_WRAP))
        wraps.put("argon2_m",   ARGON2_BASE_KB)
        wraps.put("argon2_t",   ARGON2_TIME)
        wraps.put("argon2_p",   ARGON2_LANES)
        File(ctx.filesDir, WRAPS_FILE).writeText(wraps.toString())
    }

    // ── Wipe — user-initiated only ───────────────────────────────────────────

    fun wipe(ctx: Context) {
        lockVault()
        listOf(DATA_FILE, WRAPS_FILE, LOAD_HISTORY_FILE).forEach { name ->
            File(ctx.filesDir, name).takeIf { it.exists() }?.let { secureDelete(it) }
        }
        try {
            val ks = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
            if (ks.containsAlias(KEK_BIO_ALIAS)) ks.deleteEntry(KEK_BIO_ALIAS)
        } catch (_: Exception) {}
    }

    // ── Private: Argon2id KDF ────────────────────────────────────────────────

    private fun deriveArgon2id(
        password: CharArray,
        salt: ByteArray,
        memKb: Int,
        time: Int,
        parallel: Int
    ): SecretKey {
        val pwBytes = charArrayToUtf8(password)
        return try {
            val params   = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memKb)
                .withIterations(time)
                .withParallelism(parallel)
                .build()
            val gen      = Argon2BytesGenerator().also { it.init(params) }
            val keyBytes = ByteArray(KEY_BYTES)
            gen.generateBytes(pwBytes, keyBytes, 0, keyBytes.size)
            val key = SecretKeySpec(keyBytes, "AES")
            Arrays.fill(keyBytes, 0)
            key
        } finally {
            Arrays.fill(pwBytes, 0)
        }
    }

    private fun charArrayToUtf8(chars: CharArray): ByteArray {
        val cb    = java.nio.CharBuffer.wrap(chars)
        val bb    = Charsets.UTF_8.encode(cb)
        val bytes = ByteArray(bb.remaining()).also { bb.get(it) }
        if (bb.hasArray()) Arrays.fill(bb.array(), 0)
        return bytes
    }

    // ── Private: AndroidKeyStore KEK-bio ─────────────────────────────────────

    private fun getOrCreateKekBio(ctx: Context? = null): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        ks.getKey(KEK_BIO_ALIAS, null)?.let { return it as SecretKey }

        val spec = KeyGenParameterSpec.Builder(
            KEK_BIO_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                // validity=0 means every-use CryptoObject mode (no time window).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val hasStrongBox = try {
                        ctx?.packageManager?.hasSystemFeature(
                            android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
                        ) == true
                    } catch (_: Exception) { false }
                    if (hasStrongBox) setIsStrongBoxBacked(true)
                    // Falls back to TEE-backed key on devices without StrongBox
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
            .also { it.init(spec) }
            .generateKey()
    }

    // ── Private: wrap / unwrap ───────────────────────────────────────────────

    private fun generateDek(): SecretKey =
        KeyGenerator.getInstance("AES")
            .also { it.init(256, SecureRandom()) }
            .generateKey()

    private fun wrapKey(dek: SecretKey, kek: SecretKey): Pair<ByteArray, ByteArray> {
        val iv         = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val dekBytes   = dek.encoded
        val wrapped    = cipher.doFinal(dekBytes)
        Arrays.fill(dekBytes, 0)
        return Pair(wrapped, iv)
    }

    private fun unwrapKey(wrapped: ByteArray, iv: ByteArray, kek: SecretKey): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding")
            .also { it.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv)) }
            .doFinal(wrapped)

    // ── Private: helpers ─────────────────────────────────────────────────────

    private fun requireDek(): SecretKey =
        _dek ?: throw IllegalStateException("Vault is locked")

    private fun zeroKey(key: SecretKey) {
        try { key.encoded?.let { Arrays.fill(it, 0) } } catch (_: Exception) {}
    }

    private fun saveWraps(
        ctx: Context,
        salt: ByteArray,
        wpCt: ByteArray, wpIv: ByteArray,
        wbCt: ByteArray?, wbIv: ByteArray?,   // nullable: null = bio unavailable
        memKb: Int, time: Int, lanes: Int
    ) {
        File(ctx.filesDir, WRAPS_FILE).writeText(
            JSONObject().apply {
                put("salt",          Base64.encodeToString(salt, Base64.NO_WRAP))
                put("wrap_pw",       Base64.encodeToString(wpCt, Base64.NO_WRAP))
                put("wrap_pw_iv",    Base64.encodeToString(wpIv, Base64.NO_WRAP))
                put("bio_available", wbCt != null)
                if (wbCt != null && wbIv != null) {
                    put("wrap_bio",    Base64.encodeToString(wbCt, Base64.NO_WRAP))
                    put("wrap_bio_iv", Base64.encodeToString(wbIv, Base64.NO_WRAP))
                }
                put("argon2_m", memKb)
                put("argon2_t", time)
                put("argon2_p", lanes)
            }.toString()
        )
    }

    @Throws(IllegalStateException::class)
    private fun loadWraps(ctx: Context): JSONObject {
        val f = File(ctx.filesDir, WRAPS_FILE)
        check(f.exists()) { "Vault not enrolled" }
        return JSONObject(f.readText())
    }

    private fun secureDelete(f: File) {
        val rng = SecureRandom()
        val buf = ByteArray(8192)
        val len = f.length()
        repeat(3) {
            f.outputStream().use { os ->
                var written = 0L
                while (written < len) {
                    rng.nextBytes(buf)
                    val n = minOf(buf.size.toLong(), len - written).toInt()
                    os.write(buf, 0, n)
                    written += n
                }
                os.fd.sync()
            }
        }
        f.delete()
    }
}