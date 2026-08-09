package com.newax.aegis.platform.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.getOrNull
import com.newax.aegis.platform.files.FileCapability
import com.newax.aegis.platform.files.FileMetadata
import com.newax.aegis.platform.files.FileRef
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * File capability for Android.
 *
 * Two path spaces, both real:
 *  - **App-private directory** rooted at [baseDir] (`context.filesDir`). Every
 *    file path is resolved against it and may not escape it — the traversal guard
 *    is the same class of protection as zip-slip (AGENTS.md R12).
 *  - **content:// URIs** from the Storage Access Framework, routed through
 *    [resolver]: read/write via streams, delete via the resolver, and tree
 *    listing via [DocumentsContract].
 *
 * Every method returns a typed [CapabilityResult]; IO failures never throw.
 */
class AndroidFileCapability(
    private val baseDir: File,
    private val resolver: ContentResolver? = null,
) : FileCapability {

    override val id: CapabilityId get() = CapabilityId.FILES

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Files",
        description = "App-private storage plus Storage Access Framework content URIs",
        privilegeLevel = PrivilegeLevel.STANDARD,
    )

    // ── Path handling ────────────────────────────────────────────────────────

    private fun isContentUri(path: String): Boolean = path.trim().startsWith("content://")

    private fun resolveFile(path: String): CapabilityResult<File> {
        val requested = File(path).let { if (it.isAbsolute) it else File(baseDir, path) }
        val canonical = try {
            requested.canonicalFile
        } catch (e: IOException) {
            return CapabilityResult.Failed("invalid path '$path': ${e.message}")
        }
        val base = try {
            baseDir.canonicalFile
        } catch (e: IOException) {
            return CapabilityResult.Failed("base directory unavailable: ${e.message}")
        }
        return if (canonical.path == base.path || canonical.path.startsWith(base.path + File.separator)) {
            CapabilityResult.Success(canonical)
        } else {
            CapabilityResult.Failed("path '$path' escapes the app-private directory")
        }
    }

    private fun uriOf(path: String): CapabilityResult<Uri> =
        try {
            CapabilityResult.Success(Uri.parse(path.trim()))
        } catch (e: Exception) {
            CapabilityResult.Failed("invalid content URI '$path': ${e.message}")
        }

    // ── Listing ──────────────────────────────────────────────────────────────

    override fun list(directory: String): CapabilityResult<List<FileRef>> {
        if (isContentUri(directory)) {
            val resolver = resolver ?: return CapabilityResult.Failed("no content resolver available")
            val tree = uriOf(directory).getOrNull() ?: return CapabilityResult.Failed("invalid tree URI '$directory'")
            return try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
                val refs = mutableListOf<FileRef>()
                resolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    while (cursor.moveToNext()) {
                        val docId = if (idCol >= 0) cursor.getString(idCol) else null
                        if (docId != null) {
                            val child = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                            refs.add(FileRef(child.toString()))
                        }
                    }
                }
                CapabilityResult.Success(refs)
            } catch (e: Exception) {
                CapabilityResult.Failed("cannot list tree '$directory': ${e.message}")
            }
        }
        return when (val dir = resolveFile(directory)) {
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
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    override fun stat(path: String): CapabilityResult<FileMetadata> {
        if (isContentUri(path)) {
            val resolver = resolver ?: return CapabilityResult.Failed("no content resolver available")
            val uri = uriOf(path).getOrNull() ?: return CapabilityResult.Failed("invalid URI '$path'")
            return try {
                var name: String? = null
                var size: Long = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
                if (name == null && size < 0) {
                    CapabilityResult.Failed("no document at '$path'")
                } else {
                    CapabilityResult.Success(
                        FileMetadata(path = path, sizeBytes = size.coerceAtLeast(0), isDirectory = false, lastModifiedMs = 0L, mimeType = null)
                    )
                }
            } catch (e: Exception) {
                CapabilityResult.Failed("cannot stat '$path': ${e.message}")
            }
        }
        return when (val file = resolveFile(path)) {
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
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    override fun readText(path: String): CapabilityResult<String> = when (val bytes = readBytes(path)) {
        is CapabilityResult.Success -> CapabilityResult.Success(String(bytes.value, Charsets.UTF_8))
        is CapabilityResult.MissingPermission -> CapabilityResult.MissingPermission(bytes.permission)
        is CapabilityResult.MissingCredential -> CapabilityResult.MissingCredential(bytes.credentialKey)
        is CapabilityResult.Disabled -> CapabilityResult.Disabled(bytes.reason)
        is CapabilityResult.Failed -> CapabilityResult.Failed(bytes.error)
    }

    override fun readBytes(path: String): CapabilityResult<ByteArray> {
        if (isContentUri(path)) {
            val resolver = resolver ?: return CapabilityResult.Failed("no content resolver available")
            val uri = uriOf(path).getOrNull() ?: return CapabilityResult.Failed("invalid URI '$path'")
            return try {
                resolver.openInputStream(uri)?.use { CapabilityResult.Success(it.readBytes()) }
                    ?: CapabilityResult.Failed("cannot open '$path'")
            } catch (e: Exception) {
                CapabilityResult.Failed("cannot read '$path': ${e.message}")
            }
        }
        return when (val file = resolveFile(path)) {
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
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun write(path: String, content: String, overwrite: Boolean, context: OperationContext): CapabilityResult<Unit> =
        writeBytes(path, content.toByteArray(Charsets.UTF_8), overwrite, context)

    override fun writeBytes(path: String, content: ByteArray, overwrite: Boolean, context: OperationContext): CapabilityResult<Unit> {
        if (isContentUri(path)) {
            val resolver = resolver ?: return CapabilityResult.Failed("no content resolver available")
            val uri = uriOf(path).getOrNull() ?: return CapabilityResult.Failed("invalid URI '$path'")
            return try {
                resolver.openOutputStream(uri, if (overwrite) "wt" else "wa")?.use { out ->
                    out.write(content)
                    CapabilityResult.Success(Unit)
                } ?: CapabilityResult.Failed("cannot open '$path' for writing")
            } catch (e: Exception) {
                CapabilityResult.Failed("cannot write '$path': ${e.message}")
            }
        }
        return when (val file = resolveFile(path)) {
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
    }

    // ── Copy / move / delete ─────────────────────────────────────────────────

    override fun copy(from: String, to: String, context: OperationContext): CapabilityResult<Unit> = when (val source = readBytes(from)) {
        is CapabilityResult.Success -> writeBytes(to, source.value, overwrite = true, context = context)
        is CapabilityResult.MissingPermission -> CapabilityResult.MissingPermission(source.permission)
        is CapabilityResult.MissingCredential -> CapabilityResult.MissingCredential(source.credentialKey)
        is CapabilityResult.Disabled -> CapabilityResult.Disabled(source.reason)
        is CapabilityResult.Failed -> CapabilityResult.Failed(source.error)
    }

    override fun move(from: String, to: String, context: OperationContext): CapabilityResult<Unit> {
        val fromContent = isContentUri(from)
        val toContent = isContentUri(to)
        if (!fromContent && !toContent) {
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
                // Fall through to copy+delete (cross-device or rename failure).
            } else {
                return when {
                    src is CapabilityResult.Failed -> CapabilityResult.Failed(src.error)
                    dst is CapabilityResult.Failed -> CapabilityResult.Failed(dst.error)
                    else -> CapabilityResult.Failed("cannot move '$from' -> '$to'")
                }
            }
        }
        return when (val copied = copy(from, to, context)) {
            is CapabilityResult.Success -> delete(from, context)
            else -> copied
        }
    }

    override fun delete(path: String, context: OperationContext): CapabilityResult<Unit> {
        if (isContentUri(path)) {
            val resolver = resolver ?: return CapabilityResult.Failed("no content resolver available")
            val uri = uriOf(path).getOrNull() ?: return CapabilityResult.Failed("invalid URI '$path'")
            return try {
                val deleted = resolver.delete(uri, null, null)
                if (deleted > 0) CapabilityResult.Success(Unit)
                else CapabilityResult.Failed("nothing deleted at '$path'")
            } catch (e: Exception) {
                CapabilityResult.Failed("cannot delete '$path': ${e.message}")
            }
        }
        return when (val file = resolveFile(path)) {
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
