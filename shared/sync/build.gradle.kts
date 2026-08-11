plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm()
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Shared JVM + Android implementation source set (java.security /
        // javax.crypto / java.io are available on both), so each expect in
        // commonMain has exactly one actual per compiled target. iOS targets
        // + iosMain actuals (Keychain + CryptoKit shim — CommonCrypto has no
        // Ed25519/X25519/AES-GCM; exact checklist in docs/SYNC_DESIGN.md §15)
        // are a Mac/Xcode job and are deliberately NOT declared here yet —
        // declaring them breaks Linux builds (K/N cannot target Apple from
        // Linux) and the Linux CI must stay green.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                // mDNS: LAN transport discovery (S3) + the proximity (Quick Share)
                // discovery actual (S4) — both compiled for JVM and Android.
                implementation("org.jmdns:jmdns:3.5.9")
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.newax.aegis.sync"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
