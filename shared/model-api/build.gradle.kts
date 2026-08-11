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

    // Apple targets — Track M1 logic-only slice: the ModelProvider contract
    // (expect-free commonMain) now compiles for macOS + iOS. Apple-target
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                // ModelRequest carries OperationContext (caller, origin, auditId) so every
                // inference is auditable metadata (ARCHITECTURE.md RULE 4). The contract is
                // part of model-api's public API, so it must be exported via api().
                api(project(":shared:platform-api"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                // The contract test constructs OperationContext(ActionOrigin) directly;
                // core reaches the main sourceset only transitively behind platform-api's
                // implementation(), so it must be on the test classpath explicitly.
                implementation(project(":shared:core"))
            }
        }
    }
}

android {
    namespace = "com.newax.aegis.modelapi"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
