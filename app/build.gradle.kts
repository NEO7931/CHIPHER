import java.util.Properties

val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase is only used by release builds; google-services.json is kept out of the repo.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.chipherlock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chipherlock.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "GROQ_API_KEY",
            "\"${localProps.getProperty("groq.api.key", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Security - Encrypted storage (AES-256)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp — used by GeminiScamAnalyzer for direct Google Gemini API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Security remediation dependencies ──
    // BiometricPrompt (REM-002, REM-005 session timeout)
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Argon2id KDF for progressive lockout (Layer 3)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Root / hook detection (anti-tamper, Phase 6 in roadmap)
    implementation("com.scottyab:rootbeer-lib:0.1.0")

    // Firebase Crashlytics (REM-006 production crash reporting)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}