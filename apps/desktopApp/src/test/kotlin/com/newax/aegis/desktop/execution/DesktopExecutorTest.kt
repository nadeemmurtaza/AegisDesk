package com.newax.aegis.desktop.execution

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.desktop.DesktopPolicyHolder
import com.newax.aegis.desktop.ExecutionAudit
import com.newax.aegis.desktop.TaskFailureKind
import com.newax.aegis.desktop.planner.DesktopGoalPlanner
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskStatus
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.desktop.AppWindow
import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.desktop.ScrollDirection
import com.newax.aegis.platform.desktop.UiTarget
import com.newax.aegis.platform.windows.AppIndexBridge
import com.newax.aegis.platform.windows.AppIndexEntry
import com.newax.aegis.platform.windows.WindowsAppIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Phase 5h — desktop GoalExecutor + ExecutionRouter ladder, mirroring Android's
 * GoalExecutor (activate → topological walk → live capability gate → FAILED
 * blocks the goal) with the desktop two-tier launch ladder (process launch →
 * WindowsDesktopCapability.activateApp).
 *
 * OS-independent by construction: the process launcher is injected into the
 * router and the Desktop capability is a fake with a forced status, so the tier
 * fallback, the guard, and the re-activation lifecycle are all verifiable
 * without a Windows OS.
 */
class DesktopExecutorTest {

    @Test
    fun `launch plan executes through the process tier with the target piped from find_app`() {
        val registry = registryWith(FakeDesktopCapability(CapabilityStatus.READY))
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val launched = mutableListOf<String>()
        val router = DesktopExecutionRouter(launchProcess = { name -> launched.add(name); true })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router) }

        assertTrue("expected success", result.isSuccess)
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
        // The pipe: find_app resolved the goal target and launch_app consumed it.
        assertEquals(listOf("spotify"), launched)
        val launchTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
            .first { it.skillId == "launch_app" }
        assertEquals(TaskStatus.COMPLETED, launchTask.status)
        assertTrue("reports the tier", launchTask.result!!.contains("PROCESS_LAUNCH"))
    }

    @Test
    fun `ladder falls back to Win32 activateApp when process launch fails`() {
        val desktop = FakeDesktopCapability(CapabilityStatus.READY)
        val registry = registryWith(desktop)
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val router = DesktopExecutionRouter(launchProcess = { false })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router) }

        assertTrue("expected success", result.isSuccess)
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
        assertEquals("spotify", desktop.activatedApp)
        assertEquals(ActionOrigin.AGENT, desktop.lastContext?.origin)
        val launchTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
            .first { it.skillId == "launch_app" }
        assertTrue("reports the fallback tier", launchTask.result!!.contains("WIN32_AUTOMATION"))
    }

    @Test
    fun `executor blocks the goal when the capability gate is not ready`() {
        val desktop = FakeDesktopCapability(CapabilityStatus.NOT_SUPPORTED)
        val registry = registryWith(desktop)
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val router = DesktopExecutionRouter(launchProcess = { true })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router) }

        assertFalse("expected failure", result.isSuccess)
        assertEquals(GoalState.BLOCKED, DesktopGoalPlanner.getState(plan.goal.id))
        // The guard lives inside: the launch sink was never reached, even though
        // the process launcher would have succeeded — the gate runs first.
        assertNull(desktop.activatedApp)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!
        assertEquals(TaskStatus.COMPLETED, graph.tasks.first { it.skillId == "find_app" }.status)
        val launchTask = graph.tasks.first { it.skillId == "launch_app" }
        assertEquals(TaskStatus.FAILED, launchTask.status)
        assertTrue("names the blocker", launchTask.result!!.contains("OPEN_APP"))
    }

    @Test
    fun `policy gate refuses a privileged skill when its mode is not AUTO`() {
        val policyFile = Files.createTempFile("exec-policy", ".json").also { Files.deleteIfExists(it) }
        val auditFile = Files.createTempFile("exec-audit", ".json").also { Files.deleteIfExists(it) }
        DesktopPolicyHolder.resetForTest()
        try {
            DesktopPolicyHolder.init(policyFile, auditFile)
            // launch_app maps to OpenApp; APPROVAL (non-AUTO) must refuse execution.
            DesktopPolicyHolder.engine().setModeOverride("OpenApp", PolicyMode.APPROVAL)
            val registry = registryWith(FakeDesktopCapability(CapabilityStatus.READY))
            val plan = DesktopGoalPlanner.plan("open spotify", registry)

            val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry) }

            assertFalse("expected failure", result.isSuccess)
            assertEquals(GoalState.BLOCKED, DesktopGoalPlanner.getState(plan.goal.id))
            val launchTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
                .first { it.skillId == "launch_app" }
            assertEquals(TaskStatus.FAILED, launchTask.status)
            assertEquals(
                "policy refusal carries the POLICY kind for the amber tag",
                TaskFailureKind.POLICY, launchTask.failureKind
            )
            assertTrue("names the policy reason", launchTask.result!!.contains("policy"))
            // The refusal is also in the policy audit trail (RULE 8).
            assertTrue(DesktopPolicyHolder.auditHistory().any { it.actionClass == "OpenApp" })
        } finally {
            DesktopPolicyHolder.resetForTest()
        }
    }

    @Test
    fun `policy gate passes when the holder is not initialized - execution degrades as before`() {
        DesktopPolicyHolder.resetForTest()
        val registry = registryWith(FakeDesktopCapability(CapabilityStatus.READY))
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val launched = mutableListOf<String>()
        val router = DesktopExecutionRouter(launchProcess = { name -> launched.add(name); true })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router) }

        assertTrue("expected success without a policy layer", result.isSuccess)
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
        assertEquals(listOf("spotify"), launched)
    }

    @Test
    fun `executor fails honestly when both ladder rungs fail`() {
        val desktop = FakeDesktopCapability(
            CapabilityStatus.READY,
            activateResult = CapabilityResult.Failed("no matching window and shell launch failed"),
        )
        val registry = registryWith(desktop)
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val router = DesktopExecutionRouter(launchProcess = { false })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router) }

        assertFalse("expected failure", result.isSuccess)
        assertEquals(GoalState.BLOCKED, DesktopGoalPlanner.getState(plan.goal.id))
        assertEquals("spotify", desktop.activatedApp)
        val launchTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
            .first { it.skillId == "launch_app" }
        assertTrue("carries the Win32 failure", launchTask.result!!.contains("Win32 activation failed"))
    }

    @Test
    fun `executor fails honestly for skills with no desktop executor`() {
        // find_file + execute_search have no platform requirements, so the gate
        // passes — and execution stops at the honest "no desktop executor" line.
        val plan = DesktopGoalPlanner.plan("find my notes", null)

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, null) }

        assertFalse("expected failure", result.isSuccess)
        assertEquals(GoalState.BLOCKED, DesktopGoalPlanner.getState(plan.goal.id))
        val first = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks.first()
        assertEquals(TaskStatus.FAILED, first.status)
        assertTrue(first.result!!.contains("no desktop executor"))
    }

    @Test
    fun `blocked goal reactivates and re-runs failed tasks once the capability clears`() {
        val badRegistry = registryWith(FakeDesktopCapability(CapabilityStatus.NOT_SUPPORTED))
        val plan = DesktopGoalPlanner.plan("open spotify", badRegistry)
        val first = runBlocking { DesktopGoalExecutor.run(plan.goal.id, badRegistry) }
        assertFalse(first.isSuccess)
        assertEquals(GoalState.BLOCKED, DesktopGoalPlanner.getState(plan.goal.id))

        // Capability resolved — re-run without re-planning (BLOCKED → ACTIVE,
        // the FAILED launch task is retried, the COMPLETED find_app is not).
        val desktop = FakeDesktopCapability(CapabilityStatus.READY)
        val goodRegistry = registryWith(desktop)
        val launched = mutableListOf<String>()
        val router = DesktopExecutionRouter(launchProcess = { name -> launched.add(name); true })

        val second = runBlocking { DesktopGoalExecutor.run(plan.goal.id, goodRegistry, router) }

        assertTrue("expected recovery", second.isSuccess)
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
        assertEquals(listOf("spotify"), launched)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!
        assertTrue(graph.tasks.all { it.status == TaskStatus.COMPLETED })
    }

    @Test
    fun `find_app resolves through the app index and launch_app uses the exact shortcut target`() {
        val registry = registryWith(FakeDesktopCapability(CapabilityStatus.READY))
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val index = WindowsAppIndex(
            FakeAppIndexBridge(
                listOf(
                    AppIndexEntry("Spotify", "Music", "C:\\Start Menu\\Programs\\Music\\Spotify.lnk"),
                )
            )
        )
        val shortcuts = mutableListOf<String>()
        val router = DesktopExecutionRouter(
            launchProcess = { false },
            launchShortcut = { path -> shortcuts.add(path); true },
        )

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router, index) }

        assertTrue("expected success", result.isSuccess)
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
        // The exact .lnk path was piped from find_app into launch_app and launched.
        assertEquals(listOf("C:\\Start Menu\\Programs\\Music\\Spotify.lnk"), shortcuts)
        val findTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
            .first { it.skillId == "find_app" }
        assertTrue("names the index match", findTask.result!!.contains("index match 'Spotify'"))
        val launchTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
            .first { it.skillId == "launch_app" }
        assertTrue("reports the exact tier", launchTask.result!!.contains("EXACT_TARGET"))
    }

    @Test
    fun `run records an audit entry with the tier used`() {
        ExecutionAudit.replaceAll(emptyList()) // isolate from other tests' runs
        val registry = registryWith(FakeDesktopCapability(CapabilityStatus.READY))
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val index = WindowsAppIndex(
            FakeAppIndexBridge(
                listOf(AppIndexEntry("Spotify", "Music", "C:\\Start Menu\\Programs\\Music\\Spotify.lnk"))
            )
        )
        val router = DesktopExecutionRouter(launchProcess = { false }, launchShortcut = { true })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router, index) }

        assertTrue("expected success", result.isSuccess)
        val entry = ExecutionAudit.recent().first { it.goalId == plan.goal.id }
        assertEquals("COMPLETED", entry.outcome)
        assertNull(entry.reason)
        assertTrue("reports the exact tier", entry.tiers.contains("EXACT_TARGET"))
        assertEquals(2, entry.taskCount)
        assertTrue("records the window", entry.completedMs >= entry.startedMs)
    }

    @Test
    fun `failed run records a BLOCKED audit entry with the reason`() {
        ExecutionAudit.replaceAll(emptyList()) // isolate from other tests' runs
        val registry = registryWith(FakeDesktopCapability(CapabilityStatus.NOT_SUPPORTED))
        val plan = DesktopGoalPlanner.plan("open spotify", registry)

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry) }

        assertFalse("expected failure", result.isSuccess)
        val entry = ExecutionAudit.recent().first { it.goalId == plan.goal.id }
        assertEquals("BLOCKED", entry.outcome)
        assertTrue("carries the blocker", entry.reason!!.contains("OPEN_APP"))
    }

    @Test
    fun `find_app falls back to the stripped target when the index has no match`() {
        val registry = registryWith(FakeDesktopCapability(CapabilityStatus.READY))
        val plan = DesktopGoalPlanner.plan("open spotify", registry)
        val index = WindowsAppIndex(FakeAppIndexBridge(emptyList()))
        val launched = mutableListOf<String>()
        val router = DesktopExecutionRouter(launchProcess = { name -> launched.add(name); true })

        val result = runBlocking { DesktopGoalExecutor.run(plan.goal.id, registry, router, index) }

        assertTrue("expected success", result.isSuccess)
        assertEquals(listOf("spotify"), launched)
        val findTask = DesktopGoalPlanner.getGraph(plan.goal.id)!!.tasks
            .first { it.skillId == "find_app" }
        assertTrue(findTask.result!!.contains("no index match"))
    }

    @Test
    fun `router launches the exact shortcut target and reports EXACT_TARGET`() {
        val router = DesktopExecutionRouter(launchProcess = { true }, launchShortcut = { true })

        val outcome = runBlocking {
            router.resolveLaunch("Spotify", null, "C:\\...\\Spotify.lnk").executor()
        }

        assertTrue(outcome is DesktopLaunchOutcome.Launched)
        assertEquals(
            DesktopExecutionTier.EXACT_TARGET,
            (outcome as DesktopLaunchOutcome.Launched).tier,
        )
    }

    @Test
    fun `router falls through to the process tier when the shortcut launch fails`() {
        val router = DesktopExecutionRouter(launchProcess = { true }, launchShortcut = { false })

        val outcome = runBlocking {
            router.resolveLaunch("Spotify", null, "C:\\...\\Spotify.lnk").executor()
        }

        assertTrue(outcome is DesktopLaunchOutcome.Launched)
        assertEquals(
            DesktopExecutionTier.PROCESS_LAUNCH,
            (outcome as DesktopLaunchOutcome.Launched).tier,
        )
    }

    @Test
    fun `router fails when no desktop capability is registered`() {
        val router = DesktopExecutionRouter(launchProcess = { false })

        val outcome = runBlocking { router.resolveLaunch("spotify", null).executor() }

        assertTrue(outcome is DesktopLaunchOutcome.Failed)
        assertTrue((outcome as DesktopLaunchOutcome.Failed).reason.contains("no Desktop capability registered"))
    }

    @Test
    fun `router refuses the Win32 tier when the capability is not operational`() {
        val desktop = FakeDesktopCapability(CapabilityStatus.NOT_SUPPORTED)
        val router = DesktopExecutionRouter(launchProcess = { false })

        val outcome = runBlocking { router.resolveLaunch("spotify", desktop).executor() }

        assertTrue(outcome is DesktopLaunchOutcome.Failed)
        assertTrue((outcome as DesktopLaunchOutcome.Failed).reason.contains("not ready"))
        assertNull(desktop.activatedApp)
    }

    /** Fake Desktop capability with forced status and recorded activateApp calls. */
    private class FakeDesktopCapability(
        private val status: CapabilityStatus,
        private val activateResult: CapabilityResult<Unit> = CapabilityResult.Success(Unit),
    ) : DesktopCapability {
        var activatedApp: String? = null
        var lastContext: OperationContext? = null

        override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
            id = id,
            version = 1,
            displayName = "Desktop",
            description = "fake desktop capability for executor tests",
            privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
            status = status,
        )

        override fun status(): CapabilityStatus = status

        override fun listWindows(): CapabilityResult<List<AppWindow>> = CapabilityResult.Success(emptyList())

        override fun activateApp(appName: String, context: OperationContext): CapabilityResult<Unit> {
            activatedApp = appName
            lastContext = context
            return activateResult
        }

        override fun click(target: UiTarget, context: OperationContext): CapabilityResult<Unit> =
            CapabilityResult.Failed("not exercised in executor tests")

        override fun typeText(target: UiTarget?, text: String, context: OperationContext): CapabilityResult<Unit> =
            CapabilityResult.Failed("not exercised in executor tests")

        override fun scroll(target: UiTarget, direction: ScrollDirection, context: OperationContext): CapabilityResult<Unit> =
            CapabilityResult.Failed("not exercised in executor tests")

        override fun waitFor(target: UiTarget, timeoutMs: Long): CapabilityResult<Boolean> =
            CapabilityResult.Failed("not exercised in executor tests")

        override fun screenshot(): CapabilityResult<ByteArray> =
            CapabilityResult.Failed("not exercised in executor tests")
    }

    private fun registryWith(desktop: FakeDesktopCapability): PlatformCapabilityRegistry =
        InMemoryPlatformCapabilityRegistry().apply { register(desktop) }

    private class FakeAppIndexBridge(private val entries: List<AppIndexEntry>) : AppIndexBridge {
        override fun enumerate(): List<AppIndexEntry> = entries
    }
}
