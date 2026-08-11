package com.newax.aegis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.hardware.SensorManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newax.aegis.ui.devconsole.DevConsoleActivity
import com.newax.aegis.assistant.ChatMessage
import com.newax.aegis.assistant.ProposedAction
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Design Tokens (REFINED_THEME.md) ────────────────────────────────────────
private val BG           = Color(0xFFF7F7F5)
private val Surface      = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val SurfaceSel   = Color(0xFFEFEFEC)
private val SurfaceStr   = Color(0xFFE7E7E2)
private val Primary      = Color(0xFF1B1B1A)
private val PrimaryPr    = Color(0xFF30302E)
private val TextPri      = Color(0xFF1B1B1A)
private val TextSec      = Color(0xFF686864)
private val TextTer      = Color(0xFF8D8D87)
private val Border       = Color(0xFFD8D8D3)

private data class NavEntry(
    val screen: Screen,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val badge: Int = 0
)

sealed class Screen(val label: String) {
    object Chat     : Screen("Chat")
    object Memory   : Screen("Memory")
    object Drafts   : Screen("Drafts")
    object Meeting  : Screen("Meeting")
    object Settings : Screen("Settings")
    object Backup   : Screen("Backup")
    object People   : Screen("People")
    object AppPermissions : Screen("App Permissions")
    object Capabilities : Screen("Capabilities")
    object Goals : Screen("Goals")
    object Nearby : Screen("Nearby")
    object Sync : Screen("Sync")
    object PolicyHistory : Screen("Policy History")
    object AgentMemory : Screen("Agent Memory")
    object Agents : Screen("Agents")
    object Skills : Screen("Skills")
}

class MainActivity : FragmentActivity() {

    private val shakeDetector by lazy { DevConsoleActivity.shakeListener(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AegisApp(
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
fun AegisApp(
    vm: MainViewModel = viewModel(),
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit
) {
    val drawerState   = rememberDrawerState(DrawerValue.Closed)
    val scope         = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Chat) }
    // Bumped when a policy-blocked goal task jumps to the Capabilities screen,
    // which scrolls itself to the Policy modes section.
    var policyScrollSignal by remember { mutableIntStateOf(0) }
    // Set when the policy history screen jumps to one action class's row: the
    // Capabilities screen scrolls to that row and highlights it, then resets via
    // onTargetHandled so a later manual visit doesn't re-scroll.
    var policyScrollTarget by remember { mutableStateOf<String?>(null) }
    val pendingDrafts by vm.pendingDrafts.collectAsStateWithLifecycle()
    val draftCount = pendingDrafts.size

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let { vm.submit(it) }
    }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary         = Primary,
            background      = BG,
            surface         = Surface,
            onPrimary       = Color.White,
            onBackground    = TextPri,
            onSurface       = TextPri,
            surfaceVariant  = SurfaceMuted,
            outline         = Border
        )
    ) {
        ModalNavigationDrawer(
            drawerState   = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Surface, modifier = Modifier.width(280.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Aegis",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 22.sp,
                        color      = TextPri
                    )
                    Spacer(Modifier.height(20.dp))
                    listOf(
                        NavEntry(Screen.Chat,     Icons.Outlined.ChatBubbleOutline, "Chat"),
                        NavEntry(Screen.Memory,   Icons.Outlined.Psychology,        "Memory"),
                        NavEntry(Screen.Drafts,   Icons.Outlined.AutoAwesome,       "Drafts", draftCount),
                        NavEntry(Screen.Meeting,  Icons.Outlined.Groups,            "Meeting"),
                        NavEntry(Screen.People,   Icons.Outlined.Person,            "People"),
                        NavEntry(Screen.Backup,   Icons.Outlined.CloudSync,         "Backup"),
                        NavEntry(Screen.Settings, Icons.Outlined.Settings,          "Settings"),
                        NavEntry(Screen.Goals, Icons.Rounded.CheckCircle,            "Goals"),
                        NavEntry(Screen.Capabilities, Icons.Rounded.Shield,         "Capabilities"),
                        NavEntry(Screen.Nearby, Icons.Outlined.NearMe,              "Nearby"),
                        NavEntry(Screen.Sync, Icons.Rounded.Sync,                  "Sync"),
                        NavEntry(Screen.AgentMemory, Icons.Outlined.Memory,         "Agent Memory"),
                        NavEntry(Screen.Agents, Icons.Outlined.SmartToy,            "Agents"),
                        NavEntry(Screen.Skills, Icons.Outlined.Build,               "Skills")
                    ).forEach { entry ->
                        NavigationDrawerItem(
                            label  = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    if (entry.badge > 0) {
                                        Box(
                                            Modifier
                                                .clip(CircleShape)
                                                .background(Color(0xFFF97316))
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (entry.badge > 99) "99+" else entry.badge.toString(),
                                                fontSize   = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color      = Color.White
                                            )
                                        }
                                    }
                                }
                            },
                            icon   = { Icon(entry.icon, contentDescription = entry.label) },
                            selected = screen == entry.screen,
                            onClick  = { screen = entry.screen; scope.launch { drawerState.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            colors   = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor   = SurfaceSel,
                                unselectedContainerColor = Color.Transparent,
                                selectedIconColor        = Primary,
                                selectedTextColor        = Primary,
                                unselectedIconColor      = TextSec,
                                unselectedTextColor      = TextSec
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(12.dp))
                    StatusBadge(vm.modelStatus, vm.modelBusy, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    Spacer(Modifier.height(20.dp))
                }
            }
        ) {
            Scaffold(
                containerColor = BG,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    when (screen) {
                                        Screen.Chat     -> "Chat"
                                        Screen.Memory   -> "Memory"
                                        Screen.Drafts   -> "Learning Drafts"
                                        Screen.Meeting  -> "Meeting"
                                        Screen.Backup   -> "Backup & Restore"
                                        Screen.People   -> "People"
                                        Screen.Settings -> "Settings"
                                        Screen.AppPermissions -> "App Permissions"
                                        Screen.Capabilities -> "Capabilities"
                                        Screen.Goals -> "Goals"
                                        Screen.AgentMemory -> "Agent Memory"
                                        Screen.Agents -> "Agents"
                                        Screen.Skills -> "Skills"
                                        Screen.Nearby -> "Nearby Share"
                                        Screen.Sync -> "Sync"
                                        Screen.PolicyHistory -> "Policy History"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize   = 18.sp,
                                    color      = TextPri
                                )
                                if (screen == Screen.Chat) {
                                    StatusBadge(vm.modelStatus, vm.modelBusy)
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = TextPri)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = BG),
                        modifier = Modifier
                    )
                }
            ) { padding ->
                when (screen) {
                    Screen.Chat     -> ChatScreen(vm, padding, voiceLauncher)
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
                        onTargetHandled = { policyScrollTarget = null }
                    )
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
                    Screen.Settings -> SettingsScreen(vm, padding, modelLauncher, onAccessibility, onNotifications, onNavigateToBackup = { screen = Screen.Backup }, onNavigateToPeople = { screen = Screen.People }, onNavigateToAppPermissions = { screen = Screen.AppPermissions }, onNavigateToSync = { screen = Screen.Sync })
                    Screen.Nearby -> NearbyShareScreen(padding)
                    Screen.Sync -> SyncScreen(padding)
                    Screen.AgentMemory -> AgentMemoryScreen(padding)
                    Screen.Agents -> AgentsScreen(padding)
                    Screen.Skills -> SkillsScreen(padding)
                }
            }
            BiometricOverlay(vm)
        }
    }
}

// ── Status Badge ─────────────────────────────────────────────────────────────
@Composable
private fun StatusBadge(status: String, busy: Boolean, modifier: Modifier = Modifier) {
    val isReady = status.contains("ready", true)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(8.dp), strokeWidth = 1.5.dp, color = TextSec)
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
            if (busy) "Processing…" else if (isReady) "Offline ready" else "Basic mode",
            fontSize = 12.sp,
            color    = TextSec
        )
    }
}

// ── Chat Screen ───────────────────────────────────────────────────────────────
@Composable
fun ChatScreen(
    vm: MainViewModel,
    padding: PaddingValues,
    voiceLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        val showEmpty = vm.messages.size <= 1 && !vm.modelBusy

        if (showEmpty) {
            EmptyState(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) { chip -> input = chip }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state               = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding      = PaddingValues(top = 12.dp, bottom = 4.dp)
            ) {
                items(vm.messages, key = { it.id }) { msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter   = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(tween(200))
                    ) {
                        ChatBubble(msg)
                    }
                }
                if (vm.modelBusy) {
                    item { TypingIndicator() }
                }
            }
        }

        vm.pendingAction?.let { ActionProposalCard(it, onApprove = vm::approve, onReject = vm::reject) }

        ChatComposer(
            input       = input,
            onInput     = { input = it },
            busy        = vm.modelBusy,
            onSubmit    = { if (input.isNotBlank()) { vm.submit(input); input = "" } },
            onMic       = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                voiceLauncher.launch(intent)
            }
        )
    }
}

// ── Empty State ───────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(modifier: Modifier, onChip: (String) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Aegis", fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = TextPri)
        Spacer(Modifier.height(8.dp))
        Text("Your private, on-device assistant.", color = TextSec, fontSize = 15.sp)
        Spacer(Modifier.height(32.dp))
        val chips = listOf("What's on my screen?", "Open an app", "Draft a reply", "What do you remember?")
        chips.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val c0 = row.getOrNull(0)
                val c1 = row.getOrNull(1)
                if (c0 != null) SuggestionChip(
                    onClick  = { onChip(c0) },
                    label    = { Text(c0, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    border   = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = Border)
                )
                if (c1 != null) SuggestionChip(
                    onClick  = { onChip(c1) },
                    label    = { Text(c1, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    border   = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = Border)
                ) else Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Processed on this device.", color = TextTer, fontSize = 12.sp)
    }
}

// ── Chat Bubble ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(msg: ChatMessage) {
    val context = LocalContext.current
    val time    = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (msg.fromUser) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart    = 18.dp,
                            topEnd      = 18.dp,
                            bottomStart = if (msg.fromUser) 18.dp else 4.dp,
                            bottomEnd   = if (msg.fromUser) 4.dp else 18.dp
                        )
                    )
                    .background(if (msg.fromUser) SurfaceSel else Primary)
                    .combinedClickable(
                        onClick      = {},
                        onLongClick  = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Aegis", msg.text))
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    msg.text,
                    color = if (msg.fromUser) TextPri else Color.White,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(time, fontSize = 11.sp, color = TextTer)
        }
    }
}

// ── Typing Indicator ──────────────────────────────────────────────────────────
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        repeat(3) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue  = 1f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(500, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(TextSec.copy(alpha = alpha))
            )
        }
    }
}

// ── Risk Badge ────────────────────────────────────────────────────────────────
private enum class Risk { Routine, Sensitive, HighImpact }

private fun ProposedAction.risk(): Risk = when (this) {
    is ProposedAction.DeleteFile, is ProposedAction.DeleteContact,
    is ProposedAction.DeleteProject, is ProposedAction.ForgetFact,
    is ProposedAction.RunScript, is ProposedAction.PostSocialMedia,
    is ProposedAction.Send, is ProposedAction.SendImage -> Risk.HighImpact

    is ProposedAction.Type, is ProposedAction.Tap, is ProposedAction.TapPixels,
    is ProposedAction.OpenApp, is ProposedAction.UpdateMemory,
    is ProposedAction.UpdateGraph, is ProposedAction.LogCommunication,
    is ProposedAction.UpdateNode, is ProposedAction.UpdateProject,
    is ProposedAction.CreateEvent, is ProposedAction.AuditSecurity,
    is ProposedAction.SearchAll -> Risk.Sensitive

    else -> Risk.Routine
}

// ── Action Proposal Card ──────────────────────────────────────────────────────
@Composable
private fun ActionProposalCard(action: ProposedAction, onApprove: () -> Unit, onReject: () -> Unit) {
    val risk = action.risk()
    val (riskLabel, riskBg, riskTxt) = when (risk) {
        Risk.HighImpact -> Triple("High Impact", Primary, Color.White)
        Risk.Sensitive  -> Triple("Sensitive",   SurfaceStr, TextPri)
        Risk.Routine    -> Triple("Routine",     SurfaceMuted, TextSec)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape  = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint     = TextSec,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Approval Required", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(riskBg)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(riskLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = riskTxt)
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Border)
            Spacer(Modifier.height(10.dp))
            Text(action.summary, color = TextSec, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(
                    onClick = onReject,
                    border  = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = TextSec)
                ) { Text("Cancel", fontSize = 14.sp) }
                Button(
                    onClick = onApprove,
                    colors  = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Approve", fontSize = 14.sp) }
            }
        }
    }
}

// ── Chat Composer ─────────────────────────────────────────────────────────────
@Composable
private fun ChatComposer(input: String, onInput: (String) -> Unit, busy: Boolean, onSubmit: () -> Unit, onMic: () -> Unit) {
    Surface(
        modifier   = Modifier.fillMaxWidth(),
        color      = BG,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface)
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMic, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.Mic, contentDescription = "Voice", tint = TextSec, modifier = Modifier.size(22.dp))
            }
            BasicTextField(input, onInput, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (input.isNotBlank()) Primary else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (input.isNotBlank()) Color.White else TextSec)
                } else {
                    IconButton(onClick = onSubmit, enabled = input.isNotBlank(), modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (input.isNotBlank()) Color.White else TextTer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicTextField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value              = value,
        onValueChange      = onValue,
        modifier           = modifier,
        placeholder        = { Text("Ask or command…", color = TextTer, fontSize = 15.sp) },
        colors             = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor          = TextPri
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = TextPri),
        singleLine = false,
        maxLines   = 5
    )
}

// ── Memory Screen ─────────────────────────────────────────────────────────────
@Composable
fun MemoryScreen(vm: MainViewModel, padding: PaddingValues) {
    val categories = listOf("personal", "business", "education", "relationships", "goals", "pain_points", "rules")
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val allCats = remember(vm.memoryVersion) { vm.memory.getAllCategories() }
    val totalCount = allCats.values.sumOf { it.size }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$totalCount memories", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text("Encrypted on this device", fontSize = 13.sp, color = TextSec)
                    }
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = TextSec, modifier = Modifier.size(20.dp))
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Categories") }

        items(categories) { cat ->
            val entries = allCats[cat].orEmpty()
            MemoryCategoryCard(
                category = cat,
                entries  = entries,
                expanded = expandedCategory == cat,
                onToggle = { expandedCategory = if (expandedCategory == cat) null else cat },
                onSave   = { updated -> vm.memory.setCategory(cat, updated); vm.bumpMemoryVersion() }
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Knowledge Graph") }

        val nodes = com.newax.aegis.engine.KnowledgeGraph.getAllNodes()
        if (nodes.isEmpty()) {
            item { EmptyChip("No graph nodes yet") }
        } else {
            items(nodes) { node ->
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(node.id, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPri)
                        if (node.properties.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            node.properties.forEach { (k, v) ->
                                Text("$k: $v", fontSize = 12.sp, color = TextSec, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Projects") }

        val projects = com.newax.aegis.engine.ProjectTracker.getAllProjects()
        if (projects.isEmpty()) {
            item { EmptyChip("No projects yet") }
        } else {
            items(projects) { p ->
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.id, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPri)
                        Text("${p.status} — ${p.notes}", fontSize = 12.sp, color = TextSec)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Communication Log") }

        val logs = com.newax.aegis.engine.CommunicationLog.getAllLogs().sortedByDescending { it.timestamp }
        if (logs.isEmpty()) {
            item { EmptyChip("No logged interactions yet") }
        } else {
            items(logs.take(20)) { log ->
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(log.contact, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPri)
                        Text(log.summary, fontSize = 12.sp, color = TextSec, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth(),
                border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSec)
            ) { Text("Clear all memory", fontSize = 14.sp) }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = Surface,
            title = { Text("Clear all memory?", fontWeight = FontWeight.SemiBold, color = TextPri) },
            text  = { Text("This permanently removes all saved facts, categories, and user data.", color = TextSec) },
            confirmButton = {
                Button(
                    onClick = { vm.memory.forgetAll(); vm.bumpMemoryVersion(); showClearDialog = false },
                    colors  = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel", color = TextSec) }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize   = 11.sp,
        fontWeight = FontWeight.Medium,
        color      = TextTer,
        modifier   = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

@Composable
private fun EmptyChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 13.sp, color = TextTer) }
}

@Composable
private fun MemoryCategoryCard(
    category: String,
    entries: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var draft by remember(category) { mutableStateOf(entries.joinToString("\n")) }

    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    category.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.Medium,
                    fontSize   = 14.sp,
                    color      = TextPri,
                    modifier   = Modifier.weight(1f)
                )
                Text("${entries.size}", fontSize = 12.sp, color = TextTer)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = TextSec,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = Border)
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value         = draft,
                        onValueChange = { draft = it },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("One fact per line", color = TextTer, fontSize = 12.sp) },
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Primary,
                            unfocusedBorderColor = Border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPri),
                        minLines = 2
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onSave(draft.lines().filter { it.isNotBlank() }) },
                        modifier = Modifier.align(Alignment.End),
                        colors   = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("Save", fontSize = 14.sp) }
                }
            }
        }
    }
}

// ── Settings Screen ───────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    padding: PaddingValues,
    modelLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit,
    onNavigateToBackup: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {},
    onNavigateToAppPermissions: () -> Unit = {},
    onNavigateToSync: () -> Unit = {}
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // ── Automation section (all groups + 2FA) ─────────────────────────
        item { AutomationSettingsSection(vm) }
        item { Spacer(Modifier.height(4.dp)) }

        // ── Self-Learning engine ───────────────────────────────────────────
        item { LearningSettingsSection(vm) }
        item { Spacer(Modifier.height(4.dp)) }

        item { SectionLabel("Offline AI Model") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isReady = vm.modelStatus.contains("ready", true)
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (isReady) Color(0xFF22C55E) else Color(0xFF94A3B8)))
                        Spacer(Modifier.width(10.dp))
                        Text(vm.modelStatus, fontSize = 14.sp, color = TextPri, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (vm.modelBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TextSec)
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick  = { modelLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled  = !vm.modelBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("Import model file", fontSize = 14.sp) }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Ambient Mode") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Continuously transcribe and summarize", fontSize = 13.sp, color = TextSec)
                    Spacer(Modifier.height(12.dp))
                    val currentMode = com.newax.aegis.voice.VoiceRecognitionService.ambientMode
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Meeting", "Lecture").forEach { mode ->
                            val sel = currentMode == mode
                            FilterChip(
                                selected = sel,
                                onClick  = {
                                    if (sel) com.newax.aegis.voice.VoiceRecognitionService.endAmbientMode()
                                    else com.newax.aegis.voice.VoiceRecognitionService.ambientMode = mode
                                },
                                label  = { Text(mode, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Primary,
                                    selectedLabelColor     = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Permissions") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column {
                    PermissionRow("Screen Access (Accessibility)", "Read and operate UI elements", onAccessibility)
                    HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
                    PermissionRow("Inbox Access (Notifications)", "Read incoming notifications", onNotifications)
                    HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
                    PermissionRow("App Permissions", "Control which apps Aegis can interact with", onNavigateToAppPermissions)
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("People") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToPeople)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFEDE9FE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("People", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text("Browse tracked contacts and their learned facts", fontSize = 12.sp, color = TextSec)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextTer, modifier = Modifier.size(18.dp))
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Device Sync") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToSync)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFDCFCE7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Device Sync", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text("Automatic encrypted sync across your devices", fontSize = 12.sp, color = TextSec)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextTer, modifier = Modifier.size(18.dp))
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("Backup & Restore") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToBackup)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFDBEAFE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Backup & Restore", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text("AES-256-GCM · Export to Google Drive or device", fontSize = 12.sp, color = TextSec)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextTer, modifier = Modifier.size(18.dp))
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel("About") }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Version", "0.1.0")
                    InfoRow("Storage", "Encrypted on device")
                    InfoRow("Network", "Offline — no data sent")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPri)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = TextSec)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextTer, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = TextSec, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = TextPri, fontFamily = FontFamily.Monospace)
    }
}

// ── Meeting Screen ────────────────────────────────────────────────────────────
@Composable
fun MeetingScreen(vm: MainViewModel, padding: PaddingValues) {
    val meetings = remember(vm.memoryVersion) { vm.memory.getCategory("meetings") }
    var showDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false; titleInput = "" },
            containerColor   = Surface,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text("New Meeting", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = TextPri) },
            text   = {
                Column {
                    Text("Title", fontSize = 13.sp, color = TextSec)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = titleInput,
                        onValueChange = { titleInput = it },
                        placeholder   = { Text("e.g. Sprint Review", color = TextTer) },
                        singleLine    = true,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Primary,
                            unfocusedBorderColor = Border,
                            cursorColor          = TextPri
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = TextPri),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (titleInput.isNotBlank()) {
                        val entry = "${titleInput.trim()} :: ${System.currentTimeMillis()}"
                        val updated = meetings.toMutableList().also { it.add(0, entry) }
                        vm.memory.setCategory("meetings", updated)
                        vm.bumpMemoryVersion()
                        titleInput = ""
                        showDialog = false
                    }
                }) { Text("Start", color = Primary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false; titleInput = "" }) {
                    Text("Cancel", color = TextSec)
                }
            }
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding      = PaddingValues(vertical = 12.dp)
    ) {
        // Header card
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = Surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${meetings.size} meetings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text("Stored on device", fontSize = 13.sp, color = TextSec)
                    }
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = TextSec, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Start new meeting
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = Primary),
                elevation = CardDefaults.cardElevation(0.dp),
                onClick   = { showDialog = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Start New Meeting", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        if (meetings.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionLabel("Past Meetings") }

            items(meetings, key = { it }) { entry ->
                val parts   = entry.split(" :: ")
                val title   = parts.getOrNull(0) ?: entry
                val tsMillis = parts.getOrNull(1)?.toLongOrNull()
                val dateStr  = if (tsMillis != null) fmt.format(Date(tsMillis)) else ""
                val isExpanded = expandedKey == entry

                Card(
                    shape     = RoundedCornerShape(16.dp),
                    colors    = CardDefaults.cardColors(containerColor = Surface),
                    border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    elevation = CardDefaults.cardElevation(0.dp),
                    onClick   = { expandedKey = if (isExpanded) null else entry }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = TextPri, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (dateStr.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(dateStr, fontSize = 12.sp, color = TextTer)
                                }
                            }
                            Icon(
                                if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null, tint = TextSec, modifier = Modifier.size(20.dp)
                            )
                        }
                        if (isExpanded) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Border)
                            Spacer(Modifier.height(12.dp))
                            Text("No notes added to this meeting yet.\nUse the chat to say \"remember that [note] for this meeting\" to add notes.", fontSize = 13.sp, color = TextSec, lineHeight = 20.sp)
                        }
                    }
                }
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Groups, contentDescription = null, tint = TextTer, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("No meetings yet", fontSize = 15.sp, color = TextSec, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap \"Start New Meeting\" above", fontSize = 13.sp, color = TextTer)
                    }
                }
            }
        }
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
                .setTitle("Aegis Security")
                .setSubtitle("Verify identity to execute sensitive action")
                .setNegativeButtonText("Cancel")
                .build()
        )
        onDispose { prompt.cancelAuthentication() }
    }
}
