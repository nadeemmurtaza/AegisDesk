plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Intel x64 Apple targets are dropped: the KMP dependency checker fails
    // when a declared Apple target cannot resolve a shared dependency variant.
    iosArm64()
    iosSimulatorArm64()

    // KGP no longer applies the shared hierarchy template implicitly — create
    // the standard intermediates (iosMain here) explicitly or `by getting`
    // fails with "KotlinSourceSet with name 'iosMain' not found".
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared:core"))
                implementation(project(":shared:database"))
                implementation(project(":shared:model-api"))
                implementation(project(":shared:sync"))
                implementation(project(":shared:desktop-sync"))
                implementation(project(":shared:platform-api"))
                implementation(project(":platform-impl:ios"))
            }
        }

        val iosMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}
