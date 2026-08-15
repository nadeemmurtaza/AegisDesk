plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    android {
        namespace = "com.newax.aegis.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()

    // Apple targets — Track M1 logic-only slice: the platform-free brain now
    // compiles for macOS + iOS. The one expect/actual pair is the Platform
    // time/lock seam (jvmMain/androidMain/appleMain actuals); everything else
    // in commonMain is platform-free. Platform adapters, the storage driver,
    // and the model runtime land in later slices. Apple-target compiles run on
    // a macOS host only (CI and this Linux sandbox keep verifying jvm + android).
    // Intel x64 Apple targets are dropped: the KMP dependency checker fails
    // when a declared Apple target cannot resolve a shared dependency variant.
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // KGP no longer applies the shared hierarchy template implicitly — keep the
    // standard intermediates explicit so Apple actuals can land (expect/actual
    // balance on every compiled target, R4).
    applyDefaultHierarchyTemplate()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // AuthorityManager publishes its decisions as a SharedFlow with
                // replay = 0, so the ENGINEERING.md §B7 invariants about which
                // events an approval path may emit are only observable from a
                // live collector. Same pin as shared/model-api's commonTest.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}
