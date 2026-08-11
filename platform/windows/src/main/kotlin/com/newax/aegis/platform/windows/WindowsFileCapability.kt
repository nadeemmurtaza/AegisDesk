package com.newax.aegis.platform.windows

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.files.FileCapability
import com.newax.aegis.platform.files.FileMetadata
import com.newax.aegis.platform.files.FileRef
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * File capability for Windows (and any JVM desktop host).
 *
 * Path resolution is real, not sandboxed: desktop files *are* the point of this
 * capability (PATCH_FILE on arbitrary project files). An optional [baseDir] is
 * honored when provided — every path is then confined inside it, with the same
 * canonical-path traversal guard the Android adapter uses (zip-slip class of
 * protection, AGENTS.md R12). With no baseDir, absolute paths are used as-is and
 * relative paths resolve against the JVM working directory.
 *
 * Every method returns a typed [CapabilityResult]; IO failures never throw.
 */
class WindowsFileCapability(
    private val baseDir: File? = null,
) : FileCapability {

    override val id: CapabilityId get() = CapabilityId.FILES

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Files",
        description = "Real file-system access; path-confined when a base directory is configured",
        privilegeLevel = PrivilegeLevel.STANDARD,
    )

    // ── Path handling ────────────────────────────────────────────────────────

    private fun resolveFile(path: String): CapabilityResult<File> {
        val requested = File(path).let { if (it.isAbsolute) it else baseDir?.resolve(it) ?: it }
        val canonical = try {
            requested.canonicalFile
        } catch (e: IOException) {
            return CapabilityResult.Failed("invalid path '$path': ${e.message}")
        }
        val base = baseDir?.let {
            try {
                it.canonicalFile
            } catch (e: IOException) {
                return CapabilityResult.Failed("base directory unavailable: ${e.message}")
            }
        }
        if (base != null && canonical.path != base.path && !canonical.path.startsWith(base.path + File.separator)) {
            return CapabilityResult.Failed("path '$path' escapes the configured base directory")
        }
        return CapabilityResult.Success(canonical)
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    override fun list(directory: String): CapabilityResult<List<FileRef>> = when (val dir = resolveFile(directory)) {
        is CapabilityResult.Success -> {
            val files = dir.value.listFiles()
            if (files == null) {
                CapabilityResult.Failed("cannot list '$directory': not a directory or unreadable")
            } else {
                CapabilityResult.Success(files.sortedBy { it.name }.map { FileRef(it.path) })
            }
        }
        is CapabilityResult.Failed -> CapabilityResult.Failed(dir.error)
        else -> CapabilityResult.Failed("cannot list '$directory'")
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    override fun stat(path: String): CapabilityResult<FileMetadata> = when (val file = resolveFile(path)) {
        is CapabilityResult.Success -> {
            val f = file.value
            if (!f.exists()) {
                CapabilityResult.Failed("no such file: '$path'")
            } else {
                CapabilityResult.Success(
                    FileMetadata(
                        path = f.path,
                        sizeBytes = f.length(),
                        isDirectory = f.isDirectory,
                        lastModifiedMs = f.lastModified(),
                        mimeType = null,
                    )
                )
            }
        }
        is CapabilityResult.Failed -> CapabilityResult.Failed(file.error)
        else -> CapabilityResult.Failed("cannot stat '$path'")
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    override fun readText(path: String): CapabilityResult<String> = when (val bytes = readBytes(path)) {
        is CapabilityResult.Success -> CapabilityResult.Success(String(bytes.value, Charsets.UTF_8))
        is CapabilityResult.MissingPermission -> CapabilityResult.MissingPermission(bytes.permission)
        is CapabilityResult.MissingCredential -> CapabilityResult.MissingCredential(bytes.credentialKey)
        is CapabilityResult.Disabled -> CapabilityResult.Disabled(bytes.reason)
        is CapabilityResult.Failed -> CapabilityResult.Failed(bytes.error)
    }

    override fun readBytes(path: String): CapabilityResult<ByteArray> = when (val file = resolveFile(path)) {
        is CapabilityResult.Success -> {
            val f = file.value
            if (!f.isFile) {
                CapabilityResult.Failed("'$path' is not a readable file")
            } else {
                try {
                    FileInputStream(f).use { CapabilityResult.Success(it.readBytes()) }
                } catch (e: IOException) {
                    CapabilityResult.Failed("cannot read '$path': ${e.message}")
                }
            }
        }
        is CapabilityResult.Failed -> CapabilityResult.Failed(file.error)
        else -> CapabilityResult.Failed("cannot read '$path'")
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun write(path: String, content: String, overwrite: Boolean, context: OperationContext): CapabilityResult<Unit> =
        writeBytes(path, content.toByteArray(Charsets.UTF_8), overwrite, context)

    override fun writeBytes(path: String, content: ByteArray, overwrite: Boolean, context: OperationContext): CapabilityResult<Unit> =
        when (val file = resolveFile(path)) {
            is CapabilityResult.Success -> {
                val f = file.value
                if (f.exists() && !overwrite) {
                    CapabilityResult.Failed("'$path' already exists and overwrite=false")
                } else {
                    try {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f, false).use { it.write(content) }
                        CapabilityResult.Success(Unit)
                    } catch (e: IOException) {
                        CapabilityResult.Failed("cannot write '$path': ${e.message}")
                    }
                }
            }
            is CapabilityResult.Failed -> CapabilityResult.Failed(file.error)
            else -> CapabilityResult.Failed("cannot write '$path'")
        }

    // ── Copy / move / delete ─────────────────────────────────────────────────

    override fun copy(from: String, to: String, context: OperationContext): CapabilityResult<Unit> =
        when (val source = readBytes(from)) {
            is CapabilityResult.Success -> writeBytes(to, source.value, overwrite = true, context = context)
            is CapabilityResult.MissingPermission -> CapabilityResult.MissingPermission(source.permission)
            is CapabilityResult.MissingCredential -> CapabilityResult.MissingCredential(source.credentialKey)
            is CapabilityResult.Disabled -> CapabilityResult.Disabled(source.reason)
            is CapabilityResult.Failed -> CapabilityResult.Failed(source.error)
        }

    override fun move(from: String, to: String, context: OperationContext): CapabilityResult<Unit> {
        val src = resolveFile(from)
        val dst = resolveFile(to)
        if (src is CapabilityResult.Success && dst is CapabilityResult.Success) {
            val moved = try {
                dst.value.parentFile?.mkdirs()
                src.value.renameTo(dst.value)
            } catch (e: Exception) {
                false
            }
            if (moved) return CapabilityResult.Success(Unit)
            // Fall through to copy+delete (cross-volume rename failure on Windows).
        } else {
            return when {
                src is CapabilityResult.Failed -> CapabilityResult.Failed(src.error)
                dst is CapabilityResult.Failed -> CapabilityResult.Failed(dst.error)
                else -> CapabilityResult.Failed("cannot move '$from' -> '$to'")
            }
        }
        return when (val copied = copy(from, to, context)) {
            is CapabilityResult.Success -> delete(from, context)
            else -> copied
        }
    }

    override fun delete(path: String, context: OperationContext): CapabilityResult<Unit> =
        when (val file = resolveFile(path)) {
            is CapabilityResult.Success -> {
                val f = file.value
                if (!f.exists()) {
                    CapabilityResult.Failed("no such file: '$path'")
                } else {
                    val deleted = try { f.delete() } catch (e: Exception) { false }
                    if (deleted) CapabilityResult.Success(Unit) else CapabilityResult.Failed("cannot delete '$path'")
                }
            }
            is CapabilityResult.Failed -> CapabilityResult.Failed(file.error)
            else -> CapabilityResult.Failed("cannot delete '$path'")
        }

    // ── Integrity ────────────────────────────────────────────────────────────

    override fun sha256(path: String): CapabilityResult<String> = when (val bytes = readBytes(path)) {
        is CapabilityResult.Success -> {
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(bytes.value)
            } catch (e: Exception) {
                return CapabilityResult.Failed("SHA-256 unavailable: ${e.message}")
            }
            CapabilityResult.Success(digest.joinToString("") { "%02x".format(it) })
        }
        is CapabilityResult.MissingPermission -> CapabilityResult.MissingPermission(bytes.permission)
        is CapabilityResult.MissingCredential -> CapabilityResult.MissingCredential(bytes.credentialKey)
        is CapabilityResult.Disabled -> CapabilityResult.Disabled(bytes.reason)
        is CapabilityResult.Failed -> CapabilityResult.Failed(bytes.error)
    }
}
