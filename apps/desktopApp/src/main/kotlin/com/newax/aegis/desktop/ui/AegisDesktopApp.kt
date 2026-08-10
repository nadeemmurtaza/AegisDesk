package com.newax.aegis.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newax.aegis.desktop.DesktopCapabilitiesHolder
import com.newax.aegis.desktop.ui.state.AppsScreenState
import com.newax.aegis.desktop.ui.state.GoalsScreenState
import com.newax.aegis.desktop.ui.state.LiveGoalRunner
import com.newax.aegis.desktop.ui.state.StatusScreenState
import com.newax.aegis.platform.windows.WindowsAppIndex
import kotlinx.coroutines.CoroutineScope

private enum class DesktopScreen { STATUS, APPS, GOALS }

/**
 * The desktop window content: side navigation (Status / Apps / Goals) over the
 * three screens, each backed by a plain-Kotlin state holder. The Goals board's
 * executor runs on [appScope] (the process scope that outlives the window's
 * recompositions) and resolves against the process-wide capability registry and
 * the Start Menu [appIndex].
 */
@Composable
fun AegisDesktopApp(
    appScope: CoroutineScope,
    appIndex: WindowsAppIndex?,
) {
    AegisTheme {
        var screen by remember { mutableStateOf(DesktopScreen.STATUS) }
        val statusState = remember { StatusScreenState() }
        val appsState = remember { AppsScreenState({ appIndex }) }
        val goalsState = remember {
            GoalsScreenState(
                scope = appScope,
                runner = LiveGoalRunner(appIndex = { appIndex }),
                registry = { DesktopCapabilitiesHolder.registry() },
            )
        }
        LaunchedEffect(Unit) { goalsState.refresh() }

        Scaffold(
            contentColor = SurfaceColor,
            containerColor = SurfaceColor
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NavigationRail(
                    containerColor = SurfaceColor,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    NavigationRailItem(
                        selected = screen == DesktopScreen.STATUS,
                        onClick = { screen = DesktopScreen.STATUS },
                        icon = { Icon(Icons.Rounded.Shield, contentDescription = "Status") },
                        label = { Text("Status") },
                    )
                    NavigationRailItem(
                        selected = screen == DesktopScreen.APPS,
                        onClick = { screen = DesktopScreen.APPS },
                        icon = { Icon(Icons.Rounded.Apps, contentDescription = "Apps") },
                        label = { Text("Apps") },
                    )
                    NavigationRailItem(
                        selected = screen == DesktopScreen.GOALS,
                        onClick = { screen = DesktopScreen.GOALS },
                        icon = { Icon(Icons.Rounded.Flag, contentDescription = "Goals") },
                        label = { Text("Goals") },
                    )
                }
                when (screen) {
                    DesktopScreen.STATUS -> StatusScreen(statusState)
                    DesktopScreen.APPS -> AppsScreen(appsState)
                    DesktopScreen.GOALS -> GoalsScreen(goalsState)
                }
            }
        }
    }
}
