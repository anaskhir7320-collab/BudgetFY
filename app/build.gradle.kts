import java.util.Properties

// Gemini is called through Firebase AI Logic, so NO Gemini API key is built
// into the app (the key lives in the Firebase console). Optional overrides in
// local.properties (git-ignored):
//   GEMINI_MODEL=gemini-flash-lite-latest         (optional) fast model, reads every receipt
//   GEMINI_ACCURATE_MODEL=gemini-flash-latest     (optional) re-reads receipts whose items
//                                                 don't add up to the total
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    // Unit tests: android.util.Log calls return defaults instead of failing.
    testOptions { unitTests.isReturnDefaultValues = true }

    namespace = "com.jmti.budgetfy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jmti.budgetfy"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "GEMINI_MODEL",
            "\"${localProps.getProperty("GEMINI_MODEL", "gemini-flash-lite-latest")}\""
        )
        buildConfigField(
            "String", "GEMINI_ACCURATE_MODEL",
            "\"${localProps.getProperty("GEMINI_ACCURATE_MODEL", "gemini-flash-latest")}\""
        )
        // minimal / low / medium / high. The fast read uses "minimal" (quickest);
        // the re-check thinks a little more because it only runs on hard receipts.
        buildConfigField(
            "String", "GEMINI_THINKING_LEVEL",
            "\"${localProps.getProperty("GEMINI_THINKING_LEVEL", "minimal")}\""
        )
        buildConfigField(
            "String", "GEMINI_ACCURATE_THINKING_LEVEL",
            "\"${localProps.getProperty("GEMINI_ACCURATE_THINKING_LEVEL", "low")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // Android core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    // Firebase AI Logic: calls Gemini through Firebase, so no Gemini key ships in the app
    // 18.1+ is needed for thinkingLevel (Gemini 3.x rejects the older thinkingBudget)
    implementation("com.google.firebase:firebase-ai:18.1.0")
    // App Check: only the real app may call AI Logic. Debug provider is
    // compiled into debug builds only; release uses Play Integrity.
    implementation("com.google.firebase:firebase-appcheck")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    // QR code of the App Check debug token (Profile > Testing), debug builds only
    debugImplementation("com.google.zxing:core:3.5.3")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")

    // ML Kit OCR
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ML Kit Document Scanner: auto edge detection, crop, deskew, shadow cleanup
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    // Reads photo rotation so images reach OCR the right way up
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // ✅ Coroutines (needed for ML Kit await())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}