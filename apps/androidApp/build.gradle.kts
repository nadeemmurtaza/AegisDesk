import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
    compileSdk = 35
    defaultConfig {
        applicationId = "com.newax.aegis"
        minSdk = 26
        targetSdk = 35
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
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

// Keep the debug APK named app-debug.apk — the module dir is androidApp, which would
// otherwise produce androidApp-debug.apk and break the CI artifact upload + preview link.
base {
    archivesName.set("app")
}

dependencies {
    implementation(project(":shared:core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    
    // Open-Source Security & Vosk
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(files("libs/vosk-android-0.3.75.aar"))
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    
    // Background Tasks & JS Sandbox
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.mozilla:rhino:1.7.15")

    // QR code for 2FA enrollment
    implementation("com.google.zxing:core:3.5.3")

    // OCR — on-device ML Kit text recognition (Latin bundled in base artifact)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    
    // On-device text embeddings (Universal Sentence Encoder via MediaPipe)
    implementation("com.google.mediapipe:tasks-text:0.10.14")

    // Room + SQLCipher are now in shared:database
    implementation(project(":shared:database"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
