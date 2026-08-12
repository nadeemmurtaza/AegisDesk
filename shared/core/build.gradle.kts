plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    android {
        namespace = "com.newax.aegis.core"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()

    // Apple targets — Track M1 logic-only slice: the platform-free brain now
    // compiles for macOS + iOS. commonMain has zero expects, so no actuals are
    // needed here; platform adapters, the storage driver, and the model runtime
    // land in later slices. Apple-target compiles run on a macOS host only
    // (CI and this Linux sandbox keep verifying jvm + android).
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
