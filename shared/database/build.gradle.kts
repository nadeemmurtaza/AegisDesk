plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("androidx.room") version "2.8.4"
}

kotlin {
    android {
        namespace = "com.newax.aegis.database"
        compileSdk = 36
        minSdk = 26
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
    // holds the TimeUtils + NewaxDatabaseConstructor actuals and the builder;
    // per-target Room KSP configs below generate the native implementations.
    // Apple-target compiles run on a macOS host only (apple.yml); CI and this
    // Linux sandbox keep verifying jvm + android.
    // Intel x64 Apple targets are dropped: androidx.sqlite:sqlite-framework
    // 2.7.0 publishes no iosX64/macosX64 variants, and the KMP dependency
    // checker fails the whole build when a declared target cannot resolve.
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // KGP no longer applies the shared hierarchy template implicitly — create
    // the standard intermediates (appleMain/iosMain) explicitly or the
    // appleMain actuals are never compiled and `findByName("appleMain")`
    // silently returns null.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":shared:core"))
                api("androidx.room:room-runtime:2.8.4")
                api("androidx.sqlite:sqlite-bundled:2.7.0")
                // required for coroutines Flow, etc in KMP
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        // RoomJournalStore is JVM+Android only — shared:sync deliberately
        // declares no Apple targets (the Keychain/CryptoKit actuals are a
        // Mac/Xcode job, docs/SYNC_DESIGN.md §15), so any commonMain
        // dependency on sync would break the Apple compiles of this module.
        // Apple builds exclude this source set and stay sync-free.
        val desktopAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                // The sync engine's types (SyncEntry, Hlc, JournalStore) — the
                // RoomJournalStore wiring slice maps them onto the DAOs. sync
                // does NOT depend on database (Track I can't use Room), so this
                // is the one-directional edge the design doc's "wiring slice"
                // describes.
                implementation(project(":shared:sync"))
            }
        }
        val androidMain by getting {
            dependsOn(desktopAndroidMain)
            dependencies {
                // SQLCipher for Android — the successor artifact (the old
                // android-database-sqlcipher coordinate is deprecated). The API is
                // net.zetetic.database.sqlcipher.SupportOpenHelperFactory, the drop-in
                // for the former net.sqlcipher SupportFactory (see the builder).
                implementation("net.zetetic:sqlcipher-android:4.17.0")
                implementation("androidx.sqlite:sqlite-ktx:2.7.0")
                implementation("androidx.room:room-ktx:2.8.4")
            }
        }
        val desktopMain by getting {
            dependsOn(desktopAndroidMain)
        }
        val appleMain = sourceSets.findByName("appleMain")
        appleMain?.dependencies {
            // The native storage driver: OS-provided SQLite on macOS/iOS.
            // linkerOpts below pull in the system libsqlite3 at link time.
            implementation("androidx.sqlite:sqlite-framework:2.7.0")
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

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Per-target KSP for Room's codegen (KSP 2.3.x target-specific
    // configurations — the ksp() KMP shorthand is deprecated).
    add("kspAndroid", "androidx.room:room-compiler:2.8.4")
    add("kspDesktop", "androidx.room:room-compiler:2.8.4")
    // Apple targets — per-target KSP for Room's codegen on native (R5: new
    // target = per-target KSP config added at the same moment).
    add("kspIosArm64", "androidx.room:room-compiler:2.8.4")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.8.4")
    add("kspMacosArm64", "androidx.room:room-compiler:2.8.4")
}
