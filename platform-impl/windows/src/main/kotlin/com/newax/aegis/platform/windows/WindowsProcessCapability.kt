package com.newax.aegis.platform.windows

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.processes.ProcessCapability
import com.newax.aegis.platform.processes.ProcessInfo
import com.newax.aegis.platform.processes.ProcessRef
import com.newax.aegis.platform.processes.ProcessSignal
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.io.File

/**
 * Process capability on Windows.
 *
 *  - [list]/[info] enumerate the real system process table via a
 *    [Kernel32.CreateToolhelp32Snapshot] snapshot — every process, not just this
 *    app's (unlike Android, where the OS hides other processes).
 *  - [launch] starts an executable with [ProcessBuilder] (typed args, no shell
 *    interpretation) and returns the real OS pid.
 *  - [signal] sends [ProcessSignal.TERMINATE] as a graceful WM_CLOSE to the
 *    process's top-level windows, falling back to [Kernel32.TerminateProcess]
 *    when it has no windows; [ProcessSignal.KILL] force-terminates directly.
 *    PAUSE/RESUME have no Win32 equivalent without external tooling, so they
 *    fail with an honest typed reason.
 *
 * All Win32 calls are guarded by an OS check: on a non-Windows JVM (dev boxes,
 * CI) the capability reports [CapabilityStatus.NOT_SUPPORTED] and every call
 * returns a typed [CapabilityResult.Failed] instead of crashing with a native
 * load error.
 */
class WindowsProcessCapability : ProcessCapability {

    override val id: CapabilityId get() = CapabilityId.PROCESSES

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Processes",
        description = "System-wide process listing, launch, and terminate",
        privilegeLevel = PrivilegeLevel.STANDARD,
    )

    override fun status(): CapabilityStatus =
        if (isWindows()) CapabilityStatus.READY else CapabilityStatus.NOT_SUPPORTED

    override fun launch(
        executable: String,
        args: List<String>,
        workingDirectory: String?,
        context: OperationContext,
    ): CapabilityResult<ProcessRef> {
        val command = executable.trim()
        if (command.isEmpty()) return CapabilityResult.Failed("empty executable")
        return try {
            val process = ProcessBuilder(listOf(command) + args).apply {
                workingDirectory?.let { directory(File(it)) }
            }.start()
            CapabilityResult.Success(ProcessRef(pid = process.pid(), name = File(command).name))
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot start '$command': ${e.message}")
        }
    }

    override fun list(): CapabilityResult<List<ProcessInfo>> {
        if (!isWindows()) return CapabilityResult.Failed("process listing requires Windows")
        val snapshot = try {
            Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        } catch (e: UnsatisfiedLinkError) {
            return CapabilityResult.Failed("Win32 process snapshot unavailable: ${e.message}")
        }
        if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
            return CapabilityResult.Failed("CreateToolhelp32Snapshot failed")
        }
        try {
            val entry = Tlhelp32.PROCESSENTRY32()
            val infos = mutableListOf<ProcessInfo>()
            var ok = Kernel32.INSTANCE.Process32First(snapshot, entry)
            while (ok) {
                infos.add(
                    ProcessInfo(
                        // Kotlin maps java.lang.Number to kotlin.Number, so the JNA
                        // IntegerType's Java intValue()/longValue() members are not
                        // visible — use the kotlin.Number conversions instead.
                        pid = entry.th32ProcessID.toLong(),
                        name = Native.toString(entry.szExeFile),
                    )
                )
                ok = Kernel32.INSTANCE.Process32Next(snapshot, entry)
            }
            return CapabilityResult.Success(infos)
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot)
        }
    }

    override fun info(ref: ProcessRef): CapabilityResult<ProcessInfo> =
        when (val result = list()) {
            is CapabilityResult.Success -> {
                val match = result.value.firstOrNull { it.pid == ref.pid }
                if (match != null) {
                    CapabilityResult.Success(match)
                } else {
                    CapabilityResult.Failed("pid ${ref.pid} is not running")
                }
            }
            is CapabilityResult.MissingPermission -> CapabilityResult.MissingPermission(result.permission)
            is CapabilityResult.MissingCredential -> CapabilityResult.MissingCredential(result.credentialKey)
            is CapabilityResult.Disabled -> CapabilityResult.Disabled(result.reason)
            is CapabilityResult.Failed -> CapabilityResult.Failed(result.error)
        }

    override fun signal(ref: ProcessRef, signal: ProcessSignal, context: OperationContext): CapabilityResult<Unit> {
        if (!isWindows()) return CapabilityResult.Failed("process control requires Windows")
        return when (signal) {
            ProcessSignal.KILL -> forceTerminate(ref.pid)
            ProcessSignal.TERMINATE -> {
                // Graceful first: ask the process's top-level windows to close. Apps can
                // ignore WM_CLOSE, so a windowless or unresponsive process falls back to
                // a hard TerminateProcess — the documented Windows behaviour.
                if (postCloseToWindows(ref.pid)) CapabilityResult.Success(Unit)
                else forceTerminate(ref.pid)
            }
            ProcessSignal.PAUSE, ProcessSignal.RESUME -> CapabilityResult.Failed(
                "$signal is not supported on Windows without external tooling (no Win32 suspend/resume API)"
            )
        }
    }

    private fun forceTerminate(pid: Long): CapabilityResult<Unit> {
        val handle = try {
            Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_TERMINATE, false, pid.toInt())
        } catch (e: UnsatisfiedLinkError) {
            return CapabilityResult.Failed("Win32 process control unavailable: ${e.message}")
        }
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            return CapabilityResult.Failed("cannot open pid $pid (access denied or not running)")
        }
        try {
            return if (Kernel32.INSTANCE.TerminateProcess(handle, 1)) {
                CapabilityResult.Success(Unit)
            } else {
                CapabilityResult.Failed("TerminateProcess failed for pid $pid")
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    /** Posts WM_CLOSE to every top-level window owned by [pid]; true when at least one existed. */
    private fun postCloseToWindows(pid: Long): Boolean {
        var matched = false
        val callback = object : WinUser.WNDENUMPROC {
            override fun callback(hWnd: WinDef.HWND, arg: Pointer): Boolean {
                val windowPid = IntByReference()
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, windowPid)
                if (windowPid.value.toLong() == pid) {
                    matched = true
                    User32.INSTANCE.PostMessage(hWnd, WinUser.WM_CLOSE, null, null)
                }
                return true
            }
        }
        return try {
            User32.INSTANCE.EnumWindows(callback, null)
            matched
        } catch (e: Exception) {
            false
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
