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
    add("kspAndroid", "androidx.room:room-compiler:2.7.0-alpha13")
    add("kspDesktop", "androidx.room:room-compiler:2.7.0-alpha13")
}

