package com.newax.aegis.platform.android

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.shell.ShellCapability
import com.newax.aegis.platform.shell.ShellCommand
import com.newax.aegis.platform.shell.ShellResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The single place a command string becomes a process on Android (ARCHITECTURE.md:
 * a bounded shell runner with policy + audit; R11: exactly one path to exec).
 *
 * Runs inside the app sandbox only: an [executable] must be an absolute path to a
 * binary the app can execute (e.g. an app-private binary or a /system/bin tool),
 * or the start fails with a typed [CapabilityResult.Failed] — the sandbox itself
 * is the boundary. [ShellCommand.timeoutMs] and [ShellCommand.maxOutputBytes] make
 * runaway or flooding commands impossible.
 *
 * Pure JVM in the run path (no android.* calls), so the bounded runner is covered
 * by plain unit tests.
 */
class AndroidShellCapability(
    private val baseDir: File? = null,
) : ShellCapability {

    override val id: CapabilityId get() = CapabilityId.SHELL

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Shell",
        description = "Bounded in-sandbox command runner (timeout + output cap)",
        privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
    )

    override fun run(command: ShellCommand, context: OperationContext): CapabilityResult<ShellResult> {
        val process: Process = try {
            ProcessBuilder(listOf(command.executable) + command.args).apply {
                val dir = command.workingDirectory?.let { File(it) } ?: baseDir
                if (dir != null) directory(dir)
                command.environment.forEach { (k, v) -> environment()[k] = v }
            }.start()
        } catch (e: IOException) {
            return CapabilityResult.Failed("cannot start '${command.executable}': ${e.message}")
        }

        // Read both streams concurrently so a full pipe can never deadlock the wait.
        val stdout = BoundedStreamReader(process.inputStream, command.maxOutputBytes)
        val stderr = BoundedStreamReader(process.errorStream, command.maxOutputBytes)
        val stdoutThread = Thread(stdout).apply { isDaemon = true; start() }
        val stderrThread = Thread(stderr).apply { isDaemon = true; start() }

        val finished = try {
            process.waitFor(command.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            return CapabilityResult.Failed("interrupted while waiting for '${command.executable}'")
        }

        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return CapabilityResult.Failed("'${command.executable}' timed out after ${command.timeoutMs}ms")
        }

        stdoutThread.join(1000)
        stderrThread.join(1000)
        return CapabilityResult.Success(ShellResult(process.exitValue(), stdout.text, stderr.text))
    }

    /** Reads a stream up to [maxBytes] on a background thread; never blocks the caller. */
    private class BoundedStreamReader(
        private val stream: InputStream,
        private val maxBytes: Int,
    ) : Runnable {
        private val sink = ByteArrayOutputStream()
        private val lock = Any()

        val text: String
            get() = synchronized(lock) { String(sink.toByteArray(), Charsets.UTF_8) }

        override fun run() {
            val buffer = ByteArray(4096)
            var total = 0
            try {
                while (total < maxBytes) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val take = minOf(n, maxBytes - total)
                    synchronized(lock) { sink.write(buffer, 0, take) }
                    total += take
                    if (take < n) break // output exceeds cap — stop reading
                }
            } catch (e: IOException) {
                // Stream closed by destroyForcibly on timeout; nothing to surface.
            }
        }
    }
}
