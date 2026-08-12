plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // KGP no longer applies the shared hierarchy template implicitly — create
    // the standard intermediates (iosMain here) explicitly or `by getting`
    // fails with "KotlinSourceSet with name 'iosMain' not found".
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared:model-api"))
                implementation(project(":shared:platform-api"))
                implementation(project(":shared:core"))
            }
        }

        val iosMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}