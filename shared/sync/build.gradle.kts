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
        // commonMain has exactly one actual per compiled target. iOS actuals
        // (Keychain/CommonCrypto via cinterop) land with Phase 0.
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
