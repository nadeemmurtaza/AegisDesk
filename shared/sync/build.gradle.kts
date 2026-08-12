plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    android {
        namespace = "com.newax.aegis.sync"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()

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
                implementation("org.jmdns:jmdns:3.6.3")
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                // OsKeyStore: DPAPI (Crypt32Util) on Windows — same version as
                // platform-impl/windows. macOS uses the JDK KeychainStore (no dep).
                implementation("net.java.dev.jna:jna-platform:5.19.1")
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                // The relay path's WebSocket client — java.net.http does not
                // exist on Android, OkHttp is the platform-standard client.
                implementation("com.squareup.okhttp3:okhttp:5.4.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
