package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.launch

/**
 * The shell family (docs/UI_DESIGN.md §8 — Shell): navigation containers and
 * the top bar, used by all four bodies — [NavDrawer] (compact),
 * [NavRail] (medium), [Sidebar] (expanded), [TopBar], [MenuBar] (desktop),
 * [CommandPalette], and [RouteScaffold].
 *
 * All navigation models are [NavItem]s; each item carries its own label and
 * icon, so the caller localizes and `commonMain` stays string-free. Badges are
 * rendered as text on an accent disc and announced as part of the item label —
 * never as colour alone (docs/UI_DESIGN.md §3.4).
 *
 * Accessibility contract:
 *  - every item meets the 44 dp floor and is one focus stop with its own
 *    label ([Role] is supplied by the underlying Material component);
 *  - the top bar's navigation icon is named by the caller
 *    ([TopBar.navigationLabel]);
 *  - the command palette's results are real buttons, never a colour-coded
 *    wall.
 */

/** One navigation entry. Icons and labels come from the caller (localized). */
data class NavItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val selected: Boolean = false,
    val badgeLabel: String? = null,
)

/** The compact navigation drawer (docs/UI_DESIGN.md §5.1.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavDrawer(
    items: List<NavItem>,
    open: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) {
        if (open) drawerState.open() else drawerState.close()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = NewaxTheme.colors.surface,
                modifier = Modifier.width(280.dp),
            ) {
                if (header != null) header()
                items.forEach { item ->
                    NavigationDrawerItem(
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.label,
                                    style = NewaxTheme.typography.body,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.badgeLabel != null) {
                                    Spacer(Modifier.width(NewaxTheme.spacing.sm))
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(NewaxTheme.colors.accentFill)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            item.badgeLabel,
                                            style = NewaxTheme.typography.caption,
                                            fontWeight = FontWeight.Bold,
                                            color = NewaxTheme.colors.onAccentFill,
                                        )
                                    }
                                }
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        selected = item.selected,
                        onClick = {
                            item.onClick()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = NewaxTheme.spacing.md),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = NewaxTheme.colors.surfaceSelected,
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = NewaxTheme.colors.textPrimary,
                            selectedTextColor = NewaxTheme.colors.textPrimary,
                            unselectedIconColor = NewaxTheme.colors.textSecondary,
                            unselectedTextColor = NewaxTheme.colors.textSecondary,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (footer != null) {
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = NewaxTheme.spacing.lg))
                    Spacer(Modifier.height(NewaxTheme.spacing.sm))
                    footer()
                    Spacer(Modifier.height(NewaxTheme.spacing.lg))
                }
            }
        },
        modifier = modifier,
    ) { content() }
}

/** The medium navigation rail: icon + label per item, one column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavRail(
    items: List<NavItem>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .width(80.dp)
            .fillMaxSize()
            .background(NewaxTheme.colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (header != null) {
            header()
            Spacer(Modifier.height(NewaxTheme.spacing.md))
        }
        items.forEach { item ->
            NavigationDrawerItem(
                label = {
                    Text(
                        item.label,
                        style = NewaxTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                selected = item.selected,
                onClick = item.onClick,
                alwaysShowLabel = true,
                modifier = Modifier.padding(horizontal = 6.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = NewaxTheme.colors.surfaceSelected,
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = NewaxTheme.colors.textPrimary,
                    selectedTextColor = NewaxTheme.colors.textPrimary,
                    unselectedIconColor = NewaxTheme.colors.textSecondary,
                    unselectedTextColor = NewaxTheme.colors.textSecondary,
                ),
            )
        }
    }
}

/** The expanded sidebar: a full-width list of [NavItem]s in a [Column]. */
@Composable
fun Sidebar(
    items: List<NavItem>,
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(NewaxTheme.colors.surface),
    ) {
        if (header != null) {
            header()
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
        }
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget()
                    .clip(NewaxTheme.shapes.card)
                    .background(if (item.selected) NewaxTheme.colors.surfaceSelected else Color.Transparent)
                    .semantics { role = Role.Button }
                    .clickable(onClick = item.onClick)
                    .padding(horizontal = NewaxTheme.spacing.lg, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (item.selected) NewaxTheme.colors.textPrimary else NewaxTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(NewaxTheme.spacing.md))
                Text(
                    item.label,
                    style = NewaxTheme.typography.body,
                    color = if (item.selected) NewaxTheme.colors.textPrimary else NewaxTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.badgeLabel != null) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(NewaxTheme.colors.accentFill)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(item.badgeLabel, style = NewaxTheme.typography.caption, fontWeight = FontWeight.Bold, color = NewaxTheme.colors.onAccentFill)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (footer != null) {
            HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = NewaxTheme.spacing.lg))
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
            footer()
        }
    }
}

/**
 * The top bar (docs/UI_DESIGN.md §5.1): title (with an optional line under
 * it), one navigation control, and an actions slot. One implementation for all
 * four bodies — the caller decides which navigation control it is:
 *
 *  - [onBack] renders a named back arrow (drill-down surfaces);
 *  - [onMenu] renders a named menu button (the app shell).
 *
 * Exactly one of [onBack]/[onMenu] should be set; when both are null no
 * navigation control is drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    underTitle: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    onMenu: (() -> Unit)? = null,
    menuLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    style = NewaxTheme.typography.heading,
                    color = NewaxTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (underTitle != null) underTitle()
            }
        },
        navigationIcon = {
            val icon = when {
                onBack != null -> Icons.AutoMirrored.Filled.ArrowBack
                onMenu != null -> Icons.Rounded.Menu
                else -> null
            }
            val label = when {
                onBack != null -> backLabel
                onMenu != null -> menuLabel
                else -> null
            }
            val onClick = onBack ?: onMenu
            if (icon != null && onClick != null) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.minimumTouchTarget(),
                ) {
                    Icon(icon, contentDescription = label, tint = NewaxTheme.colors.textPrimary)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = NewaxTheme.colors.bg,
            navigationIconContentColor = NewaxTheme.colors.textPrimary,
            titleContentColor = NewaxTheme.colors.textPrimary,
        ),
        modifier = modifier,
    )
}

/** One top-level desktop menu with its items. */
data class MenuEntry(
    val label: String,
    val items: List<MenuItemEntry>,
)

data class MenuItemEntry(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The desktop menu bar (docs/UI_DESIGN.md §5.2): a row of top-level menus,
 * each opening a [DropdownMenu] of items. Every entry is a 44 dp target with
 * its own label.
 */
@Composable
fun MenuBar(
    menus: List<MenuEntry>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(NewaxTheme.colors.surface),
    ) {
        menus.forEach { menu -> MenuHost(menu) }
    }
}

@Composable
private fun MenuHost(menu: MenuEntry) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.minimumTouchTarget(),
        ) { Text(menu.label, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textPrimary) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu.items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, style = NewaxTheme.typography.body, color = NewaxTheme.colors.textPrimary) },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/**
 * The command palette (docs/UI_DESIGN.md §6): a dialog with a search field
 * over [results]; each result is a full-width 44 dp button. Fully controlled —
 * the caller filters and supplies [results], so the palette works over any
 * index (skills, files, routes) without owning a query store.
 */
@Composable
fun CommandPalette(
    open: Boolean,
    onDismiss: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<String>,
    onResult: (String) -> Unit,
    placeholder: String,
    emptyLabel: String,
    modifier: Modifier = Modifier,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NewaxTheme.colors.surface,
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(placeholder, style = NewaxTheme.typography.body, color = NewaxTheme.colors.textTertiary)
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = NewaxTheme.colors.textSecondary)
                },
                singleLine = true,
                shape = NewaxTheme.shapes.card,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NewaxTheme.colors.textSecondary,
                    unfocusedBorderColor = NewaxTheme.colors.borderStrong,
                    focusedContainerColor = NewaxTheme.colors.surface,
                    unfocusedContainerColor = NewaxTheme.colors.surface,
                ),
            )
        },
        text = {
            if (results.isEmpty()) {
                Text(emptyLabel, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(results, key = { it }) { result ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .minimumTouchTarget()
                                .clip(NewaxTheme.shapes.card)
                                .semantics { role = Role.Button }
                                .clickable(onClick = { onResult(result) })
                                .padding(horizontal = NewaxTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                result,
                                style = NewaxTheme.typography.body,
                                color = NewaxTheme.colors.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        modifier = modifier,
    )
}

/**
 * A route shell: [TopBar] (title + optional back) over [content], with the
 * loading / empty / error slots the spec requires every screen to have
 * (docs/UI_DESIGN.md §3.6). Exactly one slot wins: [loading] beats [error],
 * [error] beats [empty], [empty] beats [content] — mirroring how the screens
 * already order their states.
 */
@Composable
fun RouteScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    loading: (@Composable () -> Unit)? = null,
    error: (@Composable () -> Unit)? = null,
    empty: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopBar(
            title = title,
            onBack = onBack,
            backLabel = backLabel,
        )
        when {
            loading != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { loading() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { error() }
            empty != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { empty() }
            else -> content(PaddingValues())
        }
    }
}
