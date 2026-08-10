plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("com.newax.aegis.MainKt")
}

dependencies {
    // Track A: the Windows platform adapters implement the shared PlatformCapabilities
    // contract; platform:windows exports platform-api via api(), so the app sees the
    // capability types without depending on the contract module directly.
    implementation(project(":platform:windows"))
}
