plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("androidx.room") version "2.7.0-alpha13"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Apple targets — Track M2: the memory fabric now compiles for macOS + iOS
    // with a native storage driver (NativeSQLiteDriver over the OS libsqlite3 —
    // the "native driver" of the ARCHITECTURE.md platform matrix). appleMain
    // holds the TimeUtils + AegisDatabaseConstructor actuals and the builder;
    // per-target Room KSP configs below generate the native implementations.
    // Apple-target compiles run on a macOS host only (apple.yml); CI and this
    // Linux sandbox keep verifying jvm + android.
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            // The sync engine's types (SyncEntry, Hlc, JournalStore) — the
            // RoomJournalStore wiring slice maps them onto the DAOs. sync does
            // NOT depend on database (Track I can't use Room), so this is the
            // one-directional edge the design doc's "wiring slice" describes.
            implementation(project(":shared:sync"))
            api("androidx.room:room-runtime:2.7.0-alpha13")
            api("androidx.sqlite:sqlite-bundled:2.5.0-alpha13")
            // required for coroutines Flow, etc in KMP
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
        androidMain.dependencies {
            implementation("net.zetetic:android-database-sqlcipher:4.5.4")
            implementation("androidx.sqlite:sqlite-ktx:2.4.0")
            implementation("androidx.room:room-ktx:2.7.0-alpha13")
        }
        val appleMain by getting {
            dependencies {
                // The native storage driver: OS-provided SQLite on macOS/iOS.
                // linkerOpts below pull in the system libsqlite3 at link time.
                implementation("androidx.sqlite:sqlite-framework:2.5.0-alpha13")
            }
        }
    }

    // NativeSQLiteDriver links the OS libsqlite3; declare it on every Apple
    // binary so downstream frameworks/apps link the system SQLite (the Room
    // KMP docs requirement for the native driver).
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.all {
            linkerOpts("-lsqlite3")
        }
    }
}

android {
    namespace = "com.newax.aegis.database"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // DO NOT ADD kspCommonMainMetadata! It causes MissingType errors in Room KMP.
    // (KSP1 is pinned repo-wide via gradle.properties ksp.useKSP2=false for the
    // same reason — the KSP2-only kspCommonMainKotlinMetadata task breaks Room KMP.)
    add("kspAndroid", "androidx.room:room-compiler:2.7.0-alpha13")
    add("kspDesktop", "androidx.room:room-compiler:2.7.0-alpha13")
    // Apple targets — per-target KSP for Room's codegen on native (R5: new
    // target = per-target KSP config added at the same moment).
    add("kspIosX64", "androidx.room:room-compiler:2.7.0-alpha13")
    add("kspIosArm64", "androidx.room:room-compiler:2.7.0-alpha13")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.7.0-alpha13")
    add("kspMacosX64", "androidx.room:room-compiler:2.7.0-alpha13")
    add("kspMacosArm64", "androidx.room:room-compiler:2.7.0-alpha13")
}
