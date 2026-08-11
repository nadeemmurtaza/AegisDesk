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

    // Apple targets — Track M1 logic-only slice: the capability contracts
    // (expect-free commonMain) now compile for macOS + iOS. Apple-target
    // compiles run on a macOS host only; CI and this Linux sandbox keep
    // verifying jvm + android.
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // ActionOrigin lives in shared:core; the contract references it for
                // OperationContext.origin (ARCHITECTURE.md RULE 4) without duplicating it.
                implementation(project(":shared:core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.newax.aegis.platformapi"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
