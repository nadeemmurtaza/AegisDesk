package com.newax.aegis.platform

import com.newax.aegis.assistant.ActionOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformCapabilityContractTest {

    @Test
    fun getOrNullReturnsValueOnlyOnSuccess() {
        assertEquals(42, CapabilityResult.Success(42).getOrNull())
        assertNull(CapabilityResult.MissingPermission<Int>("android.permission.READ_CONTACTS").getOrNull())
        assertNull(CapabilityResult.MissingCredential<Int>("WHATSAPP_ACCESS_TOKEN").getOrNull())
        assertNull(CapabilityResult.Disabled<Int>("turned off in settings").getOrNull())
        assertNull(CapabilityResult.Failed<Int>("boom").getOrNull())
    }

    @Test
    fun isSuccessIsFalseForEveryFailureMode() {
        assertTrue(CapabilityResult.Success(Unit).isSuccess())
        assertFalse(CapabilityResult.MissingPermission<Unit>("p").isSuccess())
        assertFalse(CapabilityResult.MissingCredential<Unit>("k").isSuccess())
        assertFalse(CapabilityResult.Disabled<Unit>("off").isSuccess())
        assertFalse(CapabilityResult.Failed<Unit>("err").isSuccess())
    }

    @Test
    fun missingPermissionAndCredentialAreDistinctFailureModes() {
        val permission: CapabilityResult<Unit> = CapabilityResult.MissingPermission("android.permission.SYSTEM_ALERT_WINDOW")
        val credential: CapabilityResult<Unit> = CapabilityResult.MissingCredential("GMAIL_ACCESS_TOKEN")
        assertTrue(permission is CapabilityResult.MissingPermission)
        assertTrue(credential is CapabilityResult.MissingCredential)
        assertEquals("android.permission.SYSTEM_ALERT_WINDOW", (permission as CapabilityResult.MissingPermission).permission)
        assertEquals("GMAIL_ACCESS_TOKEN", (credential as CapabilityResult.MissingCredential).credentialKey)
    }

    @Test
    fun operationContextCarriesAuditMetadata() {
        val ctx = OperationContext.create("facebook-agent", ActionOrigin.BACKGROUND)
        assertEquals("facebook-agent", ctx.caller)
        assertEquals(ActionOrigin.BACKGROUND, ctx.origin)
        assertTrue(ctx.auditId.startsWith("facebook-agent-"))
        val second = OperationContext.create("facebook-agent", ActionOrigin.BACKGROUND)
        assertNotEquals(ctx.auditId, second.auditId)
    }

    @Test
    fun descriptorDefaultsAreOperationalAndOffline() {
        val descriptor = CapabilityDescriptor(
            id = CapabilityId.FILES,
            version = 1,
            displayName = "Files",
            description = "File-system access",
            privilegeLevel = PrivilegeLevel.READ_ONLY,
        )
        assertEquals(CapabilityStatus.READY, descriptor.status)
        assertTrue(descriptor.offline)
        assertNull(descriptor.requiredPermission)
        assertNull(descriptor.requiredCredentialKey)
        assertTrue(CapabilityStatus.READY.isOperational)
        assertFalse(CapabilityStatus.MISSING_PERMISSION.isOperational)
        assertFalse(CapabilityStatus.NOT_SUPPORTED.isOperational)
    }

    @Test
    fun registryRegistersLooksUpAndUnregisters() {
        val registry = InMemoryPlatformCapabilityRegistry()
        val files = StubCapability(CapabilityId.FILES)
        val secrets = StubCapability(CapabilityId.SECRETS)

        assertTrue(registry.register(files))
        assertFalse(registry.register(files)) // duplicate id rejected
        assertTrue(registry.register(secrets))

        assertEquals(files, registry.get(CapabilityId.FILES))
        assertEquals(secrets, registry.get(CapabilityId.SECRETS))
        assertNull(registry.get(CapabilityId.SYSTEM))
        assertEquals(listOf(files, secrets), registry.all())

        assertTrue(registry.unregister(CapabilityId.FILES))
        assertFalse(registry.unregister(CapabilityId.FILES))
        assertNull(registry.get(CapabilityId.FILES))
        assertEquals(listOf(secrets), registry.all())
    }

    private class StubCapability(override val id: CapabilityId) : PlatformCapability {
        override fun descriptor(): CapabilityDescriptor =
            CapabilityDescriptor(
                id = id,
                version = 1,
                displayName = id.name,
                description = "test double",
                privilegeLevel = PrivilegeLevel.READ_ONLY,
            )
    }
}
