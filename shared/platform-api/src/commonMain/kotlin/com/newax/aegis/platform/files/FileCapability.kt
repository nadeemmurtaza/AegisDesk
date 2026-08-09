package com.newax.aegis.platform.files

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapability

data class FileRef(val path: String)

data class FileMetadata(
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val lastModifiedMs: Long,
    val mimeType: String? = null,
)

/**
 * File-system access. Reads are read-only; every mutating or destructive operation
 * requires [OperationContext] so the authority spine and audit ledger can attribute
 * it (ARCHITECTURE.md RULE 4, RULE 8).
 */
interface FileCapability : PlatformCapability {
    override val id: CapabilityId get() = CapabilityId.FILES

    fun list(directory: String): CapabilityResult<List<FileRef>>
    fun stat(path: String): CapabilityResult<FileMetadata>
    fun readText(path: String): CapabilityResult<String>
    fun readBytes(path: String): CapabilityResult<ByteArray>

    fun write(path: String, content: String, overwrite: Boolean = true, context: OperationContext): CapabilityResult<Unit>
    fun writeBytes(path: String, content: ByteArray, overwrite: Boolean = true, context: OperationContext): CapabilityResult<Unit>
    fun copy(from: String, to: String, context: OperationContext): CapabilityResult<Unit>
    fun move(from: String, to: String, context: OperationContext): CapabilityResult<Unit>
    fun delete(path: String, context: OperationContext): CapabilityResult<Unit>

    /**
     * SHA-256 of the file contents — the integrity check patch/overwrite skills
     * carry (ARCHITECTURE.md Coding Agent: PATCH_FILE carries expectedSha256).
     */
    fun sha256(path: String): CapabilityResult<String>
}
