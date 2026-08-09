package com.newax.aegis.platform.android

import android.content.Context
import android.content.Intent
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.processes.ProcessCapability
import com.newax.aegis.platform.processes.ProcessInfo
import com.newax.aegis.platform.processes.ProcessRef
import com.newax.aegis.platform.processes.ProcessSignal
import java.io.File

/**
 * Process capability within what Android actually allows a third-party app to do:
 *  - [list]/[info] see **this app's own** processes only (the OS hides others since
 *    API 26), with per-process PSS memory from ActivityManager.
 *  - [launch] starts an app by package name (launcher intent); an absolute path to
 *    an executable runs it via ProcessBuilder in the app sandbox.
 *  - [signal] can only terminate this app's own process; everything else fails with
 *    an honest reason — Android grants apps no cross-process signal authority.
 */
class AndroidProcessCapability(
    private val androidContext: Context,
) : ProcessCapability {

    override val id: CapabilityId get() = CapabilityId.PROCESSES

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Processes",
        description = "Own-process listing, package/app launch, own-process termination",
        privilegeLevel = PrivilegeLevel.STANDARD,
    )

    override fun launch(
        executable: String,
        args: List<String>,
        workingDirectory: String?,
        context: OperationContext,
    ): CapabilityResult<ProcessRef> {
        val command = executable.trim()
        if (command.isEmpty()) return CapabilityResult.Failed("empty executable")
        // A bare name with no path separators is a package name; launch it as an app.
        if (!command.contains(File.separatorChar) && !command.contains('/')) {
            if (args.isNotEmpty()) {
                return CapabilityResult.Failed("apps do not take argv; args: $args")
            }
            val intent = try {
                androidContext.packageManager.getLaunchIntentForPackage(command)
            } catch (e: Exception) {
                null
            } ?: return CapabilityResult.Failed("no launcher activity for package '$command'")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                androidContext.startActivity(intent)
                CapabilityResult.Success(ProcessRef(pid = android.os.Process.myPid(), name = command))
            } catch (e: Exception) {
                CapabilityResult.Failed("cannot launch '$command': ${e.message}")
            }
        }
        // Otherwise treat it as an executable path inside the app sandbox.
        return try {
            val process = ProcessBuilder(listOf(command) + args).apply {
                workingDirectory?.let { directory(File(it)) }
            }.start()
            CapabilityResult.Success(ProcessRef(pid = process.pid().toLong(), name = command))
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot start '$command': ${e.message}")
        }
    }

    override fun list(): CapabilityResult<List<ProcessInfo>> {
        val running = try {
            activityManager().runningAppProcesses
        } catch (e: Exception) {
            return CapabilityResult.Failed("cannot read running processes: ${e.message}")
        } ?: return CapabilityResult.Failed("runningAppProcesses unavailable")
        return CapabilityResult.Success(running.map { infoOf(it) })
    }

    override fun info(ref: ProcessRef): CapabilityResult<ProcessInfo> {
        val match = try {
            activityManager().runningAppProcesses?.firstOrNull { it.pid == ref.pid.toInt() }
        } catch (e: Exception) {
            null
        }
        return if (match != null) {
            CapabilityResult.Success(infoOf(match))
        } else {
            CapabilityResult.Failed("pid ${ref.pid} is not a running process of this app")
        }
    }

    override fun signal(ref: ProcessRef, signal: ProcessSignal, context: OperationContext): CapabilityResult<Unit> {
        if (ref.pid != android.os.Process.myPid().toLong()) {
            return CapabilityResult.Failed("Android apps cannot signal other processes (pid ${ref.pid})")
        }
        return when (signal) {
            ProcessSignal.TERMINATE -> {
                android.os.Process.killProcess(ref.pid.toInt())
                CapabilityResult.Success(Unit)
            }
            else -> CapabilityResult.Failed("$signal is not supported for Android app processes")
        }
    }

    private fun activityManager(): android.app.ActivityManager =
        androidContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

    private fun infoOf(process: android.app.ActivityManager.RunningAppProcessInfo): ProcessInfo {
        val memoryBytes = try {
            activityManager().getProcessMemoryInfo(intArrayOf(process.pid))
                .firstOrNull()?.totalPss?.let { it * 1024L }
        } catch (e: Exception) {
            null
        }
        return ProcessInfo(
            pid = process.pid.toLong(),
            name = process.processName,
            commandLine = null,
            cpuPercent = null,
            memoryBytes = memoryBytes,
        )
    }
}
