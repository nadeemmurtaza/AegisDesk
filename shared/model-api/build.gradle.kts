plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    android {
        namespace = "com.newax.aegis.modelapi"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()

    // Apple targets — Track M1 logic-only slice: the ModelProvider contract
    // (expect-free commonMain) now compiles for macOS + iOS. Apple-target
    // compiles run on a macOS host only; CI and this Linux sandbox keep
    // verifying jvm + android.
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
                // ModelRequest carries OperationContext (caller, origin, auditId) so every
                // inference is auditable metadata (ARCHITECTURE.md RULE 4). The contract is
                // part of model-api's public API, so it must be exported via api().
                api(project(":shared:platform-api"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                // The contract test constructs OperationContext(ActionOrigin) directly;
                // core reaches the main sourceset only transitively behind platform-api's
                // implementation(), so it must be on the test classpath explicitly.
                implementation(project(":shared:core"))
            }
        }
    }
}
