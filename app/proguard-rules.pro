# CipherLock — ProGuard / R8 keep rules
# Companion to REM-003. Last reviewed: May 2026.

# Keep line numbers for crash analytics (Firebase Crashlytics symbolicates these)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── AndroidX Security (EncryptedSharedPreferences + MasterKey) ──────────────
# These classes are accessed via reflection by the security-crypto library.
-keep class androidx.security.crypto.** { *; }
-keepclassmembers class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ─── BiometricPrompt ──────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }
-keep class androidx.core.hardware.fingerprint.** { *; }

# ─── BouncyCastle (Argon2id, Layer 3) ────────────────────────────────────────
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# ─── Autofill Service (system instantiates by class name) ────────────────────
-keep class com.chipherlock.app.ChipherLockAutofillService { *; }
-keep public class * extends android.service.autofill.AutofillService

# ─── PasswordEntry (JSON serialization reflects on field names) ───────────────
# Fields must keep their names because JSON keys are derived from them.
-keepclassmembers class com.chipherlock.app.PasswordEntry {
    <fields>;
    <init>(...);
}

# ─── SMS / system-registered receivers and services ──────────────────────────
-keep class com.chipherlock.app.SmsReceiver { *; }
-keep class com.chipherlock.app.MmsReceiver { *; }
-keep class com.chipherlock.app.HeadlessSmsService { *; }
-keep class com.chipherlock.app.SmsComposeActivity { *; }

# ─── OkHttp (used by GroqScamAnalyzer) ───────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ─── Kotlin metadata (required for reflection-based libraries) ────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ─── Application entry points ─────────────────────────────────────────────────
-keep class com.chipherlock.app.MainActivity { *; }

# Everything else (VaultFragment internals, PasswordGenerator, analyzer, etc.)
# is freely obfuscated by R8 — that is the goal.
# ─── Strip ALL android.util.Log calls in release builds ──────────────────────
# This is a compile-time nuclear option — even if a Log.* call slips past
# the BuildConfig.DEBUG guards in code, R8 will remove it entirely from the
# release APK. No log output can reach logcat in production.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# ─── Timber (if added in future) ─────────────────────────────────────────────
# If Timber logging library is ever added as a dependency, uncomment this block:
# -assumenosideeffects class timber.log.Timber {
#     public static *** v(...);
#     public static *** d(...);
#     public static *** i(...);
#     public static *** w(...);
#     public static *** e(...);
# }