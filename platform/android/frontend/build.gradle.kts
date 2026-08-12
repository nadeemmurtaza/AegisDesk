import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials live in keystore.properties (gitignored — see
// keystore.properties.example), never in source. When the file is absent the
// release build falls back to debug signing so assembleRelease still builds;
// production releases require the real keystore + properties to be present.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.newax.aegis"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.newax.aegis"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile") ?: "keystore.jks")
                storePassword = keystoreProperties.getProperty("storePassword") ?: ""
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: "key0"
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                // No keystore.properties: keep assembleRelease buildable with debug signing.
                signingConfigs.getByName("debug")
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Room entities live in shared:database (which runs its own Room KSP and
    // exports schemas there); the androidTest migration tests read the exported
    // schema JSONs straight from that single source of truth.
    sourceSets {
        getByName("androidTest").assets.srcDirs("$rootDir/shared/database/schemas")
    }
}

// AGP 9 built-in Kotlin: jvmTarget for the app's Kotlin compilation (AGENTS.md
// baseline — 17 for all modules; it also defaults to targetCompatibility, but
// stay explicit so the JDK 23 host cannot drift it).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Keep the debug APK named app-debug.apk — the module dir is androidApp, which would
// otherwise produce androidApp-debug.apk and break the CI artifact upload + preview link.
base {
    archivesName.set("app")
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:model-api"))
    implementation(project(":shared:sync"))
    implementation(project(":platform:android:backend"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    
    // Open-Source Security & Vosk
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(files("libs/vosk-android-0.3.75.aar"))
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    
    // Background Tasks & JS Sandbox
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.mozilla:rhino:1.9.1")

    // QR code for 2FA enrollment
    implementation("com.google.zxing:core:3.5.4")

    // OCR — on-device ML Kit text recognition (Latin bundled in base artifact)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    
    // On-device text embeddings (Universal Sentence Encoder via MediaPipe)
    implementation("com.google.mediapipe:tasks-text:1.0.0")

    // Room + SQLCipher are now in shared:database
    implementation(project(":shared:database"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
