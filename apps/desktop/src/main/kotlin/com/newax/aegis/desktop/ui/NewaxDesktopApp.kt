package com.newax.aegis.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newax.aegis.desktop.DesktopCapabilitiesHolder
import com.newax.aegis.desktop.GoalsStore
import com.newax.aegis.desktop.ui.state.AppsScreenState
import com.newax.aegis.desktop.ui.state.AuditScreenState
import com.newax.aegis.desktop.ui.state.ChatState
import com.newax.aegis.desktop.ui.state.GoalsScreenState
import com.newax.aegis.desktop.ui.state.LiveGoalRunner
import com.newax.aegis.desktop.ui.state.PolicyScreenState
import com.newax.aegis.desktop.ui.state.StatusScreenState
import com.newax.aegis.platform.windows.WindowsAppIndex
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop window content under the unified IA (docs/UI_DESIGN.md §5.1):
 *
 * - **Chat (home, route 1.1/1.2)** — the conversation sidebar + the thread,
 *   the product's main surface, backed by [ChatState] (Room-persisted
 *   conversations, live model streaming).
 * - **Tasks (3.1)** — the Goals board ([GoalsScreen]).
 * - **Capabilities (4.1 / 4.3)** — Status + the Start Menu app index.
 * - **Settings (5.3.1 / 5.3.1.3)** — Policy & Capabilities + the audit trail.
 *
 * The previous flat five-screen rail (Status/Apps/Goals/Policy/Audit) is gone:
 * chat is the home, and the other screens are reached through the sidebar's
 * section links, matching the route tree.
 */
@Composable
fun NewaxDesktopApp(
    appScope: CoroutineScope,
    appIndex: WindowsAppIndex?,
    store: GoalsStore? = null,
    chatState: ChatState,
) {
    NewaxTheme {
        // Chat is home — the window opens on the thread pane.
        var section by remember { mutableStateOf<SidebarSection?>(null) }
        var subView by remember { mutableStateOf<SidebarSubView?>(null) }

        val statusState = remember { StatusScreenState() }
        val appsState = remember { AppsScreenState({ appIndex }) }
        val goalsState = remember {
            GoalsScreenState(
                scope = appScope,
                runner = LiveGoalRunner(appIndex = { appIndex }),
                store = store,
                registry = { DesktopCapabilitiesHolder.registry() },
            )
        }
        val auditState = remember { AuditScreenState() }
        val policyState = remember { PolicyScreenState() }
        LaunchedEffect(Unit) {
            goalsState.refresh()
            auditState.refresh()
            policyState.refresh()
            chatState.refreshConversations()
        }

        Scaffold(
            contentColor = MaterialTheme.colorScheme.surface,
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                ConversationSidebar(
                    state = chatState,
                    selectedSection = section,
                    selectedSubView = subView,
                    onSelectSection = { s, v ->
                        section = s
                        subView = v
                    },
                    onOpenConversation = { id ->
                        chatState.openConversation(id)
                        section = null
                        subView = null
                    },
                    onNewChat = {
                        chatState.newChat()
                        section = null
                        subView = null
                    },
                )
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                    when (section) {
                        null -> ThreadScreen(state = chatState, onNewChat = { chatState.newChat() })
                        SidebarSection.TASKS -> GoalsScreen(
                            state = goalsState,
                            onOpenPolicy = {
                                section = SidebarSection.SETTINGS
                                subView = SidebarSubView.POLICY
                            },
                        )
                        SidebarSection.CAPABILITIES -> when (subView ?: SidebarSubView.STATUS) {
                            SidebarSubView.STATUS -> StatusScreen(statusState)
                            SidebarSubView.APPS -> AppsScreen(appsState)
                            else -> StatusScreen(statusState)
                        }
                        SidebarSection.SETTINGS -> when (subView ?: SidebarSubView.POLICY) {
                            SidebarSubView.POLICY -> PolicyScreen(policyState)
                            SidebarSubView.AUDIT -> AuditScreen(auditState)
                            else -> PolicyScreen(policyState)
                        }
                    }
                }
            }
        }
    }
}
