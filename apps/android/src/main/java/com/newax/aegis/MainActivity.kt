package com.newax.aegis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.components.ConfirmDialog
import com.newax.aegis.ui.components.ConversationRow
import com.newax.aegis.ui.components.TopBar
import com.newax.aegis.ui.devconsole.DevConsoleActivity
import com.newax.aegis.ui.state.ConversationListState
import com.newax.aegis.ui.state.VoiceCaptureState
import com.newax.aegis.ui.theme.NewaxTheme
import com.newax.aegis.voice.VoiceCaptureSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class NavEntry(
    val screen: Screen,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int,
    val badge: Int = 0
)

// T3.2 — screen labels are string-resource ids, not literals. `labelRes` is the
// drawer label; the top bar resolves a few screens to a longer title via
// [topBarTitleRes].
sealed class Screen(@androidx.annotation.StringRes val labelRes: Int) {
    object Chat     : Screen(R.string.nav_chat)
    object Conversations : Screen(R.string.nav_chats)
    // T3.5d — the compact-IA section homes: Memory (2.x) and Tasks (3.x) have
    // sub-routes and get a home; Capabilities (4.1) and Settings (5) link
    // straight to their landing screens from the drawer.
    object MemoryHome : Screen(R.string.nav_memory)
    object TasksHome : Screen(R.string.nav_tasks)
    object Memory   : Screen(R.string.nav_memory)
    object Drafts   : Screen(R.string.nav_drafts)
    object Meeting  : Screen(R.string.nav_meeting)
    object Settings : Screen(R.string.nav_settings)
    object Backup   : Screen(R.string.nav_backup)
    object People   : Screen(R.string.nav_people)
    object AppPermissions : Screen(R.string.screen_title_app_permissions)
    object Capabilities : Screen(R.string.nav_capabilities)
    object Goals : Screen(R.string.nav_goals)
    object Nearby : Screen(R.string.nav_nearby)
    object Sync : Screen(R.string.nav_sync)
    object PolicyHistory : Screen(R.string.screen_title_policy_history)
    object AgentMemory : Screen(R.string.nav_agent_memory)
    object Agents : Screen(R.string.nav_agents)
    object Skills : Screen(R.string.nav_skills)
    object Updates : Screen(R.string.nav_updates)
    // T3.5e — route 4.3: the apps index, reached from Capabilities (4.1 → 4.3).
    object AppsIndex : Screen(R.string.apps_index_title)
}

/** The top-bar title for a screen — most screens reuse their drawer label. */
private fun topBarTitleRes(screen: Screen): Int = when (screen) {
    Screen.Drafts -> R.string.screen_title_learning_drafts
    Screen.Backup -> R.string.screen_title_backup_restore
    Screen.Updates -> R.string.screen_title_pending_updates
    Screen.Nearby -> R.string.screen_title_nearby_share
    else -> screen.labelRes
}

class MainActivity : FragmentActivity() {

    private val shakeDetector by lazy { DevConsoleActivity.shakeListener(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NewaxApp(
                onAccessibility  = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onNotifications  = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
            )
        }
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.let { sm ->
            shakeDetector.register(sm)
        }
    }

    override fun onDestroy() {
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.let { sm ->
            shakeDetector.unregister(sm)
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewaxApp(
    vm: MainViewModel = viewModel(),
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit
) {
    val drawerState   = rememberDrawerState(DrawerValue.Closed)
    val scope         = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Chat) }
    // Clear-chat confirm (T3.0b → T3.5a): deletion goes through vm.clearChat()
    // → ChatHistoryStore.deleteConversation → ConversationDao.deleteConversation
    // — the one transactional delete path (blocks, then messages, then the
    // row), now scoped to the active conversation.
    var showClearChatDialog by remember { mutableStateOf(false) }
    // Bumped when a policy-blocked goal task jumps to the Capabilities screen,
    // which scrolls itself to the Policy modes section.
    var policyScrollSignal by remember { mutableIntStateOf(0) }
    // Set when the policy history screen jumps to one action class's row: the
    // Capabilities screen scrolls to that row and highlights it, then resets via
    // onTargetHandled so a later manual visit doesn't re-scroll.
    var policyScrollTarget by remember { mutableStateOf<String?>(null) }
    val pendingDrafts by vm.pendingDrafts.collectAsStateWithLifecycle()
    val draftCount = pendingDrafts.size
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    // T3.5d — the drawer (route 1.1 compact) shows the same rows and relative
    // time labels as the full 1.1 route, via the same tested holder.
    val conversationListState = remember { ConversationListState() }
    // RLAIF-E live notification (docs/AGENTS_DESIGN.md §evolution): poll the
    // staging registry; the count feeds the Updates nav badge and the banner
    // that pops up the minute a patch is staged.
    var pendingUpdateCount by remember { mutableIntStateOf(com.newax.aegis.agents.LearningEngine.pendingCount()) }
    var updateBannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            pendingUpdateCount = com.newax.aegis.agents.LearningEngine.pendingCount()
            delay(4000)
        }
    }
    LaunchedEffect(pendingUpdateCount) {
        if (pendingUpdateCount == 0) updateBannerDismissed = false
    }

    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }

    // T3.5b — route 1.12: the export sheet's state and the SAF write. The
    // rendered bytes are stashed at launch ([pendingExport]) and written to the
    // Uri the picker returns; the outcome drives the sheet's live region.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingExportName by remember { mutableStateOf("") }
    var exportStatus by remember { mutableStateOf<ExportStatus>(ExportStatus.Idle) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Unable to open the chosen file")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        exportStatus = if (result.isSuccess) {
            // The provider's name for the written file; falls back to the name
            // we suggested (a file name is data, not chrome).
            ExportStatus.Done(uri.lastPathSegment ?: pendingExportName)
        } else {
            ExportStatus.Failed(result.exceptionOrNull()?.message ?: "write failed")
        }
    }

    // T3.5b — the chat overlays: 1.4 model sheet, 1.9 step detail, 1.12 export.
    var showModelSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showStepDetail by remember { mutableStateOf(false) }
    var stepDetailAction by remember { mutableStateOf<ProposedAction?>(null) }
    var chatMenuOpen by remember { mutableStateOf(false) }
    // Close the step-detail sheet when the pending action changes or resolves
    // (approve/reject/auto-execute) — it must never show a stale action, or
    // pop open for a queued one the user did not ask about.
    LaunchedEffect(vm.pendingAction) {
        if (showStepDetail && vm.pendingAction != stepDetailAction) {
            showStepDetail = false
        }
    }

    // T3.5c — route 1.10 voice capture. The mic opens a capture sheet instead
    // of the one-shot system recognizer: the live level meter needs
    // onRmsChanged and the running transcript needs onPartialResults, which
    // only the SpeechRecognizer API provides. The recognizer is a platform
    // seam ([VoiceCaptureSession]); [VoiceCaptureState] holds the sheet's
    // phases and is unit-tested. RECORD_AUDIO is a runtime permission on
    // minSdk 26, so the first capture requests it — denial opens the sheet in
    // the permission error phase rather than failing silently.
    var showVoiceCapture by remember { mutableStateOf(false) }
    val voiceState = remember { VoiceCaptureState() }
    val voiceSession = remember { VoiceCaptureSession(context) }
    DisposableEffect(voiceSession) {
        onDispose { voiceSession.destroy() }
    }

    fun beginVoiceCapture() {
        voiceState.reset()
        if (!voiceSession.available) {
            voiceState.onError(R.string.voice_error_unavailable)
            showVoiceCapture = true
            return
        }
        voiceSession.start(object : VoiceCaptureSession.Listener {
            override fun onReady() { voiceState.onListening() }
            override fun onRmsChanged(rms: Float) { voiceState.onAmplitude(rms) }
            override fun onPartial(text: String) { voiceState.onPartial(text) }
            override fun onFinal(text: String) { voiceState.onFinal(text) }
            override fun onError(labelRes: Int) { voiceState.onError(labelRes) }
        })
        showVoiceCapture = true
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginVoiceCapture()
        } else {
            voiceState.onError(R.string.voice_error_permission)
            showVoiceCapture = true
        }
    }

    fun openVoiceCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            beginVoiceCapture()
        }
    }

    // The shared theme (shared:ui) installs the Material 3 colour scheme from
    // the same tokens, and provides NewaxTheme.colors / .typography / .spacing /
    // .shapes to everything below. darkTheme now follows the system setting
    // (T3.3 — every screen reads NewaxTheme.colors at the call site, so the
    // dark palette applies everywhere). The in-app override lives behind the
    // Settings theme route (UI_DESIGN.md 5.1.4).
    NewaxTheme {
        ModalNavigationDrawer(
            drawerState   = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = NewaxTheme.colors.surface, modifier = Modifier.width(280.dp)) {
                    val drawerItemColors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor   = NewaxTheme.colors.surfaceSelected,
                        unselectedContainerColor = Color.Transparent,
                        selectedIconColor        = NewaxTheme.colors.textPrimary,
                        selectedTextColor        = NewaxTheme.colors.textPrimary,
                        unselectedIconColor      = NewaxTheme.colors.textSecondary,
                        unselectedTextColor      = NewaxTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.nav_brand),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 22.sp,
                        color      = NewaxTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(20.dp))
                    // T3.5d — route 1.1 compact: the drawer IS the conversation
                    // list. New chat and search sit at the top, the threads
                    // below, and the four sections + model footer at the
                    // bottom. The full 1.1 route stays as ConversationsScreen
                    // (reached through the search row).
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.action_new_chat), fontSize = 15.sp) },
                        icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        selected = false,
                        onClick = {
                            vm.newChat()
                            screen = Screen.Chat
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = drawerItemColors,
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.conversations_search_hint), fontSize = 15.sp) },
                        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        selected = false,
                        onClick = {
                            screen = Screen.Conversations
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = drawerItemColors,
                    )
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    if (conversations.isEmpty()) {
                        Text(
                            stringResource(R.string.conversations_empty_title),
                            fontSize = 13.sp,
                            color = NewaxTheme.colors.textTertiary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    } else {
                        // The same rows as the full 1.1 route, so the drawer
                        // and the route cannot disagree about recency or titles.
                        LazyColumn(Modifier.weight(1f)) {
                            items(conversations, key = { it.id }) { summary ->
                                ConversationRow(
                                    title = summary.title.ifBlank { stringResource(R.string.conversation_untitled) },
                                    timeLabel = conversationListState.relativeTimeLabel(summary.updatedAtMs, System.currentTimeMillis()),
                                    onClick = {
                                        vm.openConversation(summary.id)
                                        screen = Screen.Chat
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    listOf(
                        NavEntry(Screen.MemoryHome, Icons.Outlined.Psychology, R.string.nav_memory, draftCount),
                        NavEntry(Screen.TasksHome, Icons.Rounded.CheckCircle, R.string.nav_tasks),
                        NavEntry(Screen.Capabilities, Icons.Rounded.Shield, R.string.nav_capabilities),
                        NavEntry(Screen.Settings, Icons.Outlined.Settings, R.string.nav_settings, pendingUpdateCount)
                    ).forEach { entry ->
                        NavigationDrawerItem(
                            label  = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(entry.labelRes), fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    if (entry.badge > 0) {
                                        Box(
                                            Modifier
                                                .clip(CircleShape)
                                                .background(Color(0xFFF97316))
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (entry.badge > 99) stringResource(R.string.badge_overflow) else entry.badge.toString(),
                                                fontSize   = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color      = Color.White
                                            )
                                        }
                                    }
                                }
                            },
                            icon   = { Icon(entry.icon, contentDescription = null) },
                            selected = screen == entry.screen,
                            onClick  = { screen = entry.screen; scope.launch { drawerState.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            colors   = drawerItemColors,
                        )
                    }
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    // T3.5b — route 1.1 item 8: the model status footer opens the
                    // model sheet (1.4). The badge itself is unchanged; the row
                    // gains a real 44 dp target.
                    StatusBadge(
                        vm.modelStatus, vm.modelBusy,
                        Modifier
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .minimumTouchTarget()
                            .clickable {
                                scope.launch { drawerState.close() }
                                showModelSheet = true
                            }
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        ) {
            Scaffold(
                containerColor = NewaxTheme.colors.bg,
                topBar = {
                    // T3.4c: the shared shell TopBar (docs/UI_DESIGN.md §8 — Shell).
                    TopBar(
                        title = stringResource(topBarTitleRes(screen)),
                        underTitle = {
                            if (screen == Screen.Chat) {
                                StatusBadge(vm.modelStatus, vm.modelBusy)
                            }
                        },
                        onMenu = { scope.launch { drawerState.open() } },
                        menuLabel = stringResource(R.string.cd_menu),
                        actions = {
                            if (screen == Screen.Chat) {
                                // T3.5b — route 1.6/1.12: the thread's overflow
                                // menu. Export opens the export sheet; Delete
                                // keeps the existing clear-chat confirm.
                                Box {
                                    IconButton(onClick = { chatMenuOpen = true }) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.cd_chat_overflow), tint = NewaxTheme.colors.textSecondary)
                                    }
                                    DropdownMenu(expanded = chatMenuOpen, onDismissRequest = { chatMenuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_export)) },
                                            onClick = {
                                                chatMenuOpen = false
                                                exportStatus = ExportStatus.Idle
                                                showExportSheet = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_delete_conversation)) },
                                            onClick = {
                                                chatMenuOpen = false
                                                showClearChatDialog = true
                                            },
                                        )
                                    }
                                }
                            }
                            if (screen == Screen.Conversations) {
                                // 1.1 — New chat (pencil-in-square icon per the spec).
                                IconButton(onClick = { vm.newChat(); screen = Screen.Chat }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_new_chat), tint = NewaxTheme.colors.textSecondary)
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                    Screen.Chat     -> ChatScreen(
                        vm,
                        padding,
                        onOpenModelSheet = { showModelSheet = true },
                        onOpenStepDetail = {
                            stepDetailAction = vm.pendingAction
                            showStepDetail = true
                        },
                        onOpenVoiceCapture = ::openVoiceCapture
                    )
                    Screen.Conversations -> ConversationsScreen(
                        vm,
                        padding,
                        onOpenThread = { id ->
                            vm.openConversation(id)
                            screen = Screen.Chat
                        },
                        onNewChat = { vm.newChat(); screen = Screen.Chat }
                    )
                    // T3.5d — the compact-IA section homes (2.x / 3.x).
                    Screen.MemoryHome -> MemoryHomeScreen(padding, draftCount) { screen = it }
                    Screen.TasksHome -> TasksHomeScreen(padding) { screen = it }
                    Screen.Memory   -> MemoryScreen(vm, padding)
                    Screen.Drafts   -> DraftsScreen(vm, padding)
                    Screen.Meeting  -> MeetingScreen(vm, padding)
                    Screen.Backup   -> BackupRestoreScreen(vm, padding)
                    Screen.People   -> PeopleScreen(vm, padding)
                    Screen.AppPermissions -> AppPermissionScreen(padding)
                    Screen.Capabilities -> CapabilitiesScreen(
                        padding,
                        policyScrollSignal = policyScrollSignal,
                        onScrollHandled = { policyScrollSignal = 0 },
                        onOpenPolicyHistory = { screen = Screen.PolicyHistory },
                        policyScrollTarget = policyScrollTarget,
                        onTargetHandled = { policyScrollTarget = null },
                        // T3.5e — routes 4.2/4.3: the apps index, and the
                        // remedy destinations for a non-operational capability.
                        onOpenAppsIndex = { screen = Screen.AppsIndex },
                        onOpenAppPermissions = { screen = Screen.AppPermissions },
                        onOpenSettings = { screen = Screen.Settings }
                    )
                    Screen.AppsIndex -> AppsIndexScreen(vm, padding)
                    Screen.PolicyHistory -> PolicyHistoryScreen(
                        padding,
                        onOpenActionClass = { actionClass ->
                            policyScrollTarget = actionClass
                            screen = Screen.Capabilities
                        }
                    )
                    Screen.Goals -> GoalsScreen(
                        padding,
                        onOpenPolicyModes = { actionClass ->
                            // Known class: scroll to + highlight that policy row.
                            // Unknown: scroll to the Policy modes section top.
                            if (actionClass != null) {
                                policyScrollTarget = actionClass
                            } else {
                                policyScrollSignal++
                            }
                            screen = Screen.Capabilities
                        }
                    )
                    Screen.Settings -> SettingsScreen(
                        vm, padding, modelLauncher, onAccessibility, onNotifications,
                        onNavigateToBackup = { screen = Screen.Backup },
                        onNavigateToPeople = { screen = Screen.People },
                        onNavigateToAppPermissions = { screen = Screen.AppPermissions },
                        onNavigateToSync = { screen = Screen.Sync },
                        // T3.5d — the settings sub-routes that left the drawer
                        // now live inside the Settings page (spec §6.7).
                        onNavigateToPolicyHistory = { screen = Screen.PolicyHistory },
                        onNavigateToNearby = { screen = Screen.Nearby },
                        onNavigateToUpdates = { screen = Screen.Updates },
                    )
                    Screen.Nearby -> NearbyShareScreen(padding)
                    Screen.Sync -> SyncScreen(padding)
                    Screen.AgentMemory -> AgentMemoryScreen(padding)
                    Screen.Agents -> AgentsScreen(padding, onContinueTask = { vm.submit(it) })
                    Screen.Skills -> SkillsScreen(padding)
                    Screen.Updates -> UpdatesScreen(padding)
                    }
                    if (pendingUpdateCount > 0 && !updateBannerDismissed) {
                        PendingUpdatesBanner(
                            count = pendingUpdateCount,
                            onOpen = { updateBannerDismissed = true; screen = Screen.Updates },
                            onDismiss = { updateBannerDismissed = true },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp)
                        )
                    }
                }
            }
            BiometricOverlay(vm)
        }
        if (showClearChatDialog) {
            // T3.4: the shared confirm dialog — destructive actions are
            // confirmed before they run (SC 3.3.4/3.3.6).
            ConfirmDialog(
                title         = stringResource(R.string.chat_clear_title),
                body          = stringResource(R.string.chat_clear_body),
                confirmLabel  = stringResource(R.string.action_clear),
                dismissLabel  = stringResource(R.string.action_cancel),
                onConfirm     = { showClearChatDialog = false; vm.clearChat() },
                onDismiss     = { showClearChatDialog = false },
                destructive   = true
            )
        }
        // T3.5b — the chat overlays, above everything else in the shell.
        if (showModelSheet) {
            ModelSheet(
                vm = vm,
                onDismiss = { showModelSheet = false },
                onImport = { modelLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onAllModelSettings = {
                    showModelSheet = false
                    screen = Screen.Settings
                },
            )
        }
        if (showExportSheet) {
            val exportTitle = conversations.firstOrNull { it.id == vm.activeConversationId }?.title.orEmpty()
            ExportSheet(
                messages = vm.transcriptForExport(),
                title = exportTitle,
                status = exportStatus,
                onDismiss = {
                    showExportSheet = false
                    exportStatus = ExportStatus.Idle
                    pendingExport = null
                    pendingExportName = ""
                },
                onExport = { text, _, suggestedName ->
                    pendingExport = text
                    pendingExportName = suggestedName
                    exportLauncher.launch(suggestedName)
                },
            )
        }
        if (showStepDetail) {
            vm.pendingAction?.let { action ->
                StepDetailSheet(
                    action = action,
                    onDismiss = { showStepDetail = false },
                    onSeeInHistory = {
                        showStepDetail = false
                        screen = Screen.PolicyHistory
                    },
                    onChangePolicy = {
                        showStepDetail = false
                        screen = Screen.Settings
                    },
                )
            }
        }
        if (showVoiceCapture) {
            // T3.5c — route 1.10: Stop inserts the transcript into the
            // composer (1.2) and returns; Cancel discards. Stop with nothing
            // heard leaves the sheet open on the nothing-recognized error
            // (the state moved to ERROR) instead of closing silently.
            VoiceCaptureSheet(
                state = voiceState,
                onStop = {
                    val transcript = voiceState.stop()
                    voiceSession.cancel()
                    if (transcript != null) {
                        vm.composerText = transcript
                        showVoiceCapture = false
                    }
                },
                onCancel = {
                    voiceState.cancel()
                    voiceSession.cancel()
                    showVoiceCapture = false
                },
            )
        }
    }
}

// ── Status Badge ─────────────────────────────────────────────────────────────
@Composable
private fun StatusBadge(status: String, busy: Boolean, modifier: Modifier = Modifier) {
    val isReady = status.contains("ready", true)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(8.dp), strokeWidth = 1.5.dp, color = NewaxTheme.colors.textSecondary)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isReady) Color(0xFF22C55E) else Color(0xFF94A3B8))
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (busy) stringResource(R.string.status_processing)
            else if (isReady) stringResource(R.string.status_offline_ready)
            else stringResource(R.string.status_basic_mode),
            fontSize = 12.sp,
            color    = NewaxTheme.colors.textSecondary
        )
    }
}

// ── Biometric Overlay ─────────────────────────────────────────────────────────
@Composable
fun BiometricOverlay(vm: MainViewModel) {
    if (!vm.biometricAuthRequested) return
    val context = LocalContext.current as FragmentActivity
    DisposableEffect(Unit) {
        val prompt = BiometricPrompt(context, ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(code: Int, err: CharSequence) {
                    vm.biometricAuthRequested = false
                    vm.reject()
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.executeApprovedAction()
                }
                override fun onAuthenticationFailed() {}
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(stringResource(R.string.biometric_title))
                .setSubtitle(stringResource(R.string.biometric_subtitle))
                .setNegativeButtonText(stringResource(R.string.action_cancel))
                .build()
        )
        onDispose { prompt.cancelAuthentication() }
    }
}
