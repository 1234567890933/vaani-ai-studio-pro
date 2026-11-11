package ai.hindidub.ui

import ai.hindidub.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Root of the app.
 *
 * Three layers: the ambient backdrop (blurred colour blobs that give the glass something to
 * pick up), the app itself arriving as a window that scales in from 92 %, and the splash that
 * hands over to it. Screen changes animate with a horizontal slide plus fade, and the bottom bar
 * is a translucent glass strip rather than an opaque Material bar.
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSplash by remember { mutableStateOf(true) }

    HindiDubberTheme(themeMode = state.settings.themeMode) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop()

            AnimatedVisibility(
                visible = !showSplash,
                enter = fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(520, easing = FastOutSlowInEasing)
                    )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                ) {
                    MainShell(vm, state)
                }
            }

            AnimatedVisibility(
                visible = showSplash,
                exit = fadeOut(tween(380)) + scaleOutTo(1.08f)
            ) {
                SplashScreen { showSplash = false }
            }
        }
    }
}

private fun scaleOutTo(target: Float) =
    androidx.compose.animation.scaleOut(
        targetScale = target,
        animationSpec = tween(380, easing = FastOutSlowInEasing)
    )

@Composable
private fun MainShell(vm: AppViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        state.notice?.let { text ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearNotice() }) { Text("OK") }
                }
            }
        }

        AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(260)) { w -> dir * w / 6 } +
                    fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(220)) { w -> -dir * w / 8 } + fadeOut(tween(180)))
            },
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

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            tonalElevation = 0.dp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            NavItem("Home", R.drawable.ic_home, state.screen == Screen.Home) { vm.go(Screen.Home) }
            NavItem("Job", R.drawable.ic_processing, state.screen == Screen.Processing) { vm.go(Screen.Processing) }
            NavItem("Player", R.drawable.ic_player, state.screen == Screen.Player) {
                state.outputPath?.let { vm.openPlayer(it) } ?: vm.go(Screen.Player)
            }
            NavItem("APIs", R.drawable.ic_cloud, state.screen == Screen.Api) { vm.go(Screen.Api) }
            NavItem("Settings", R.drawable.ic_settings, state.screen == Screen.Settings) { vm.go(Screen.Settings) }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                painterResource(icon),
                contentDescription = label,
                modifier = Modifier.scale(if (selected) 1.12f else 1f).alpha(if (selected) 1f else 0.7f)
            )
        },
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
                )
            }
        }
        trailing?.invoke()
    }
}
