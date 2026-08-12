plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        summary = "Newax Aegis iOS App"
        homepage = "https://github.com/nadeemmurtaza/AegisDesk"
        pod("Firebase/Core")
    }

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
