package com.newax.aegis.ios

import com.newax.aegis.platform.PlatformCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalForeignApi::class)
fun main() {
    println("Newax Aegis iOS — offline-first assistant")
    println("shared/model-api · platform-impl:ios · Phase M · SwiftUI (TBD)")

    // Bootstrap platform capabilities
    val capabilities = PlatformCapabilities()
    println("Capabilities: ${capabilities.capabilities().joinToString(", ")}")

    // Keep process alive for testing
    Thread.sleep(Long.MAX_VALUE)
}