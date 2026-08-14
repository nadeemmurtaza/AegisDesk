plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    android {
        namespace = "com.newax.aegis.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // The JVM target is the desktop body for BOTH Windows and macOS: apps/desktop
    // and apps/macos are Compose Desktop (kotlin.jvm + compose.desktop.currentOs)
    // applications, so they consume this module's jvm variant.
    jvm()

    // iOS is the only Apple target a Compose UI module can declare. Compose
    // Multiplatform ships native UI for iOS and JVM Desktop; it has no
    // macosArm64 UI artifacts, so — unlike shared:core, which is platform-free
    // logic and does declare macosArm64 — this module must not. The macOS body
    // is served by jvm() above, not by a native target.
    iosArm64()
    iosSimulatorArm64()

    // KGP no longer applies the shared hierarchy template implicitly — keep the
    // standard intermediates explicit, matching shared:core (R4).
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // api, not implementation: NewaxColors exposes Color and
                // NewaxTypography exposes TextStyle in their public signatures,
                // so every consumer needs these types on its own compile
                // classpath.
                api(compose.runtime)
                api(compose.ui)
                api(compose.foundation)
                api(compose.material3)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
