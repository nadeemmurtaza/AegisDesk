/**
 * Entry point for the Newax macOS body — the macOS member of the 4-device mesh
 * (docs/SYNC_DESIGN.md §2, Track M).
 *
 * Default mode: opens a Compose Desktop window with the sync surface — the
 * automatic-sync status, this device's pairing code, SAS-confirmed pairing,
 * the paired-device list, and the memory profile synced from paired devices.
 *
 * CLI mode (--cli): the same engine behind a prompt loop:
 *   sync                  status (device, peers, loop, memory)
 *   sync code             this device's pairing code
 *   sync pair <code>      SAS-confirmed pairing
 *   sync unpair <id>      remove a paired device
 *   sync peer <id> <host:port>   direct-connect bootstrap when mDNS is blocked
 *   sync memory           the synced memory profile
 *
 * The engine is [DesktopSync] (shared:desktop-sync) — identical to the Windows
 * desktop body, so a macOS machine joins the same mesh automatically.
 */
package com.newax.aegis.macos

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.newax.aegis.desktopsync.DesktopSync
import com.newax.aegis.macos.ui.SyncScreen
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.contains("--cli")) {
        runBlocking { cliMain() }
    } else {
        windowMain()
    }
}

private fun windowMain() {
    DesktopSync.start("macOS " + System.getProperty("os.name"))
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Newax Aegis — macOS · Device Sync",
            state = rememberWindowState(size = DpSize(980.dp, 720.dp)),
        ) {
            SyncScreen()
        }
    }
    DesktopSync.stop()
}

private suspend fun cliMain() {
    DesktopSync.start("macOS " + System.getProperty("os.name"))
    println()
    println("  Newax Aegis macOS — device sync shell")
    println("  commands: newax sync | newax sync code | newax sync pair <code> | newax sync unpair <id> |")
    println("            newax sync peer <id> <host:port> | newax sync memory | exit")
    println()
    while (true) {
        print("  > ")
        val line = readLine()?.trim() ?: break
        if (line.isBlank() || line.equals("exit", ignoreCase = true)) break
        val cmd = if (line.startsWith("newax ", ignoreCase = true)) line.substring(6).trim() else line
        if (cmd.equals("sync", ignoreCase = true) || cmd.startsWith("sync ", ignoreCase = true)) {
            printSyncCommand(if (cmd.length > 4) cmd.substring(4).trim() else "")
        } else {
            println("    unknown — try \"newax sync\" for status")
        }
    }
    DesktopSync.stop()
}

private fun printSyncCommand(arg: String) {
    println()
    println("  ── Sync ────────────────────────────────────────────────")
    when {
        arg.isEmpty() || arg.equals("status", ignoreCase = true) -> {
            println("    device : ${DesktopSync.displayName()} (${DesktopSync.deviceId()})")
            println("    peers  : ${DesktopSync.peers().size}")
            println("    status : ${DesktopSync.status()}")
            println("    memory : ${DesktopSync.memoryCategories().size} category(ies) synced")
        }
        arg.equals("code", ignoreCase = true) -> {
            println("    This device's pairing code — paste it into the other device's pair field:")
            println("    ${DesktopSync.pairingCode()}")
        }
        arg.startsWith("pair ", ignoreCase = true) -> {
            val code = arg.substring(5).trim()
            if (code.isEmpty()) {
                println("    usage: newax sync pair <their-code>")
            } else {
                val sas = DesktopSync.sasFor(DesktopSync.pairingCode(), code)
                if (sas == null) {
                    println("    ✗ That doesn't look like a valid pairing code.")
                } else {
                    print("    Both devices show SAS $sas — confirm it matches (y/N): ")
                    System.out.flush()
                    if (readLine()?.trim()?.equals("y", ignoreCase = true) == true) {
                        val peer = DesktopSync.pairWith(code)
                        if (peer == null) println("    ✗ Pairing failed (invalid code or self-pair).")
                        else println("    ✓ Paired with ${peer.displayName} (${peer.deviceId})")
                    } else {
                        println("    cancelled")
                    }
                }
            }
        }
        arg.startsWith("unpair ", ignoreCase = true) -> {
            DesktopSync.unpair(arg.substring(7).trim())
            println("    ✓ removed")
        }
        arg.startsWith("peer ", ignoreCase = true) -> {
            val parts = arg.substring(5).trim().split(Regex("\\s+"))
            if (parts.size != 2) {
                println("    usage: newax sync peer <deviceId> <host:port>")
            } else {
                DesktopSync.setPeerAddress(parts[0], parts[1])
                println("    ✓ direct address stored for ${parts[0]}")
            }
        }
        arg.startsWith("memory", ignoreCase = true) -> {
            val memory = DesktopSync.memory()
            if (memory.isEmpty()) {
                println("    No synced memory yet — pair a device and wait for a sync round.")
            } else {
                memory.toSortedMap().forEach { (category, facts) ->
                    println("    · $category")
                    facts.forEach { println("        - $it") }
                }
            }
        }
        else -> println("    commands: newax sync (empty|status), code, pair <code>, unpair <id>, peer <id> <host:port>, memory")
    }
    println()
}
