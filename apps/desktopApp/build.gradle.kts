plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("MainKt")
}

dependencies {
    // The desktop GGUF model provider (platform:windows — Phase 5c) fulfills
    // the shared ModelProvider contract for desktop inference.
    implementation(project(":platform:windows"))

    // ActionOrigin (OperationContext.origin) lives in shared:core; platform-api
    // declares it implementation-only, so consumers that build OperationContext
    // (the GoalExecutor's Win32 launch context — Phase 5h) must declare core
    // explicitly — same as platform:windows and platform:android.
    implementation(project(":shared:core"))

    // Coroutines for the interactive prompt loop (runBlocking)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JVM unit tests for the process-wide holders (Phase 5e)
    testImplementation("junit:junit:4.13.2")
}