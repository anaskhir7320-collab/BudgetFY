import java.util.Properties

// Secrets live in local.properties (git-ignored), never in source:
//   GEMINI_API_KEY=your-key-from-aistudio.google.com
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
            "String", "GEMINI_API_KEY",
            "\"${localProps.getProperty("GEMINI_API_KEY", "")}\""
        )
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
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

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