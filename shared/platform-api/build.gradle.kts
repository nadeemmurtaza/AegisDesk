plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    android {
        namespace = "com.newax.aegis.platformapi"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()

    // Apple targets — Track M1 logic-only slice: the capability contracts
    // (expect-free commonMain) now compile for macOS + iOS. Apple-target
    // compiles run on a macOS host only; CI and this Linux sandbox keep
    // verifying jvm + android.
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    // KGP no longer applies the shared hierarchy template implicitly — keep the
    // standard intermediates explicit so Apple actuals can land (expect/actual
    // balance on every compiled target, R4).
    applyDefaultHierarchyTemplate()

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
