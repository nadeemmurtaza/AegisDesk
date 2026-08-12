package com.newax.aegis.platform.windows

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.secrets.SecretAvailability
import com.newax.aegis.platform.secrets.SecretSource
import com.newax.aegis.platform.secrets.SecretsCapability
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Properties

/**
 * Secrets capability on Windows: a DPAPI-protected vault.
 *
 * Values are encrypted with [Crypt32Util.cryptProtectData] (CryptProtectData) —
 * Windows' per-user, machine-bound encryption — and persisted as Base64 blobs in
 * `<dataDir>/secrets.properties` (default `~/.aegis`). Only the current Windows
 * user can decrypt them; the raw value exists only transiently in memory.
 *
 * Contract discipline (AGENTS.md invariant 4): [availability] is all the model
 * layer ever sees; [read] is the single guarded path (R11) and returns the raw
 * value only to the executor, never into prompt context, source, or logs.
 *
 * DPAPI requires Windows: on any other OS the capability reports
 * [CapabilityStatus.NOT_SUPPORTED] and calls fail with a typed reason.
 */
class WindowsSecretsCapability(
    private val dataDir: File = File(System.getProperty("user.home") ?: ".", ".aegis"),
) : SecretsCapability {

    private val lock = Any()

    override val id: CapabilityId get() = CapabilityId.SECRETS

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Secrets",
        description = "DPAPI-protected credential vault; values are references, never prompt content",
        privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
        requiredCredentialKey = null, // self-contained vault, no external credential needed
    )

    override fun status(): CapabilityStatus =
        if (isWindows()) CapabilityStatus.READY else CapabilityStatus.NOT_SUPPORTED

    override fun availability(key: String): CapabilityResult<SecretAvailability> = synchronized(lock) {
        if (!isWindows()) return CapabilityResult.Failed("DPAPI secrets require Windows")
        try {
            CapabilityResult.Success(
                SecretAvailability(key = key, available = load().containsKey(key), source = SecretSource.OS_VAULT)
            )
        } catch (e: IOException) {
            CapabilityResult.Failed("cannot read the secrets vault: ${e.message}")
        }
    }

    override fun listKeys(): CapabilityResult<List<String>> = synchronized(lock) {
        if (!isWindows()) return CapabilityResult.Failed("DPAPI secrets require Windows")
        try {
            CapabilityResult.Success(load().keys.map { it.toString() }.sorted())
        } catch (e: IOException) {
            CapabilityResult.Failed("cannot read the secrets vault: ${e.message}")
        }
    }

    override fun store(key: String, value: String, context: OperationContext): CapabilityResult<Unit> = synchronized(lock) {
        if (!isWindows()) return CapabilityResult.Failed("DPAPI secrets require Windows")
        if (key.isBlank()) return CapabilityResult.Failed("empty secret key")
        try {
            val protectedBytes = Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))
            val props = load()
            props.setProperty(key, Base64.getEncoder().encodeToString(protectedBytes))
            save(props)
            CapabilityResult.Success(Unit)
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot store secret '$key': ${e.message}")
        }
    }

    override fun read(key: String, context: OperationContext): CapabilityResult<String> = synchronized(lock) {
        if (!isWindows()) return CapabilityResult.Failed("DPAPI secrets require Windows")
        try {
            val encoded = load().getProperty(key) ?: return CapabilityResult.Failed("no secret stored for '$key'")
            val decrypted = try {
                Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encoded))
            } catch (e: Exception) {
                return CapabilityResult.Failed("cannot decrypt secret '$key' (wrong user or corrupted vault): ${e.message}")
            }
            CapabilityResult.Success(String(decrypted, Charsets.UTF_8))
        } catch (e: IOException) {
            CapabilityResult.Failed("cannot read the secrets vault: ${e.message}")
        }
    }

    override fun delete(key: String, context: OperationContext): CapabilityResult<Unit> = synchronized(lock) {
        if (!isWindows()) return CapabilityResult.Failed("DPAPI secrets require Windows")
        try {
            val props = load()
            if (props.remove(key) == null) return CapabilityResult.Failed("no secret stored for '$key'")
            save(props)
            CapabilityResult.Success(Unit)
        } catch (e: IOException) {
            CapabilityResult.Failed("cannot write the secrets vault: ${e.message}")
        }
    }

    private val vaultFile: File
        get() = File(dataDir, "secrets.properties")

    private fun load(): Properties {
        val props = Properties()
        if (vaultFile.exists()) {
            vaultFile.inputStream().use { props.load(it) }
        }
        return props
    }

    private fun save(props: Properties) {
        dataDir.mkdirs()
        vaultFile.outputStream().use { props.store(it, null) }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
