package ai.hindidub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.hindidub.R

/**
 * Root of the app: theme, notice bar, animated screen switch, bottom navigation.
 *
 * The screen switch is an [AnimatedContent] keyed on the sealed [Screen], so adding a screen
 * later cannot silently fall through — the `when` is exhaustive.
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    HindiDubberTheme(themeMode = state.settings.themeMode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                state.notice?.let { text ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearNotice() }) { Text("OK") }
                        }
                    }
                }

                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "screen",
                    modifier = Modifier.weight(1f)
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(vm, state)
                        Screen.Processing -> ProcessingScreen(vm, state)
                        Screen.Player -> PlayerScreen(vm, state)
                        Screen.Settings -> SettingsScreen(vm, state)
                        Screen.Api -> ApiScreen(vm, state)
                    }
                }

                NavigationBar {
                    NavItem(
                        label = "Home", icon = R.drawable.ic_home,
                        selected = state.screen == Screen.Home
                    ) { vm.go(Screen.Home) }
                    NavItem(
                        label = "Job", icon = R.drawable.ic_processing,
                        selected = state.screen == Screen.Processing
                    ) { vm.go(Screen.Processing) }
                    NavItem(
                        label = "Player", icon = R.drawable.ic_player,
                        selected = state.screen == Screen.Player
                    ) {
                        state.outputPath?.let { vm.openPlayer(it) } ?: vm.go(Screen.Player)
                    }
                    NavItem(
                        label = "APIs", icon = R.drawable.ic_cloud,
                        selected = state.screen == Screen.Api
                    ) { vm.go(Screen.Api) }
                    NavItem(
                        label = "Settings", icon = R.drawable.ic_settings,
                        selected = state.screen == Screen.Settings
                    ) { vm.go(Screen.Settings) }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = label) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium) }
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        trailing?.invoke()
    }
}
