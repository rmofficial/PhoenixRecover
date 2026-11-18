package com.phoenix.recover.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.ui.components.PhoenixBackground
import com.phoenix.recover.ui.screens.CarveScreen
import com.phoenix.recover.ui.screens.HomeScreen
import com.phoenix.recover.ui.screens.PermissionScreen
import com.phoenix.recover.ui.screens.PreviewDialog
import com.phoenix.recover.ui.screens.ResultsScreen
import com.phoenix.recover.ui.screens.RootScreen
import com.phoenix.recover.ui.screens.ScanScreen
import com.phoenix.recover.ui.screens.SettingsScreen
import com.phoenix.recover.ui.screens.VaultScreen
import com.phoenix.recover.ui.theme.Accents
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.PhoenixTheme
import com.phoenix.recover.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@Composable
fun PhoenixApp(vm: RecoverViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val accent = Accents.firstOrNull { it.key == state.settings.accentKey } ?: Accents[0]
    val activity = LocalContext.current as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler {
        if (!vm.back()) activity?.finish()
    }

    PhoenixTheme(accent = accent) {
        PhoenixBackground {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                val needsPermission = !state.hasAccess && !state.permissionDismissed
                if (needsPermission) {
                    PermissionScreen(vm)
                } else {
                    AnimatedContent(
                        targetState = state.screen,
                        transitionSpec = {
                            (fadeIn(androidx.compose.animation.core.tween(180)) +
                                slideInHorizontally { it / 24 })
                                .togetherWith(fadeOut(androidx.compose.animation.core.tween(120)))
                        },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.HOME -> HomeScreen(state, vm)
                            Screen.SCAN -> ScanScreen(state, vm)
                            Screen.CARVE -> CarveScreen(state, vm)
                            Screen.RESULTS -> ResultsScreen(state, vm)
                            Screen.VAULT -> VaultScreen(state, vm)
                            Screen.SETTINGS -> SettingsScreen(state, vm)
                            Screen.ROOT -> RootScreen(state, vm)
                        }
                    }
                }

                ToastHost(vm, state.toast)
                state.showPreview?.let { file ->
                    PreviewDialog(file, state, vm)
                }
            }
        }
    }
}

@Composable
private fun ToastHost(vm: RecoverViewModel, message: String?) {
    val accent = LocalAccent.current
    LaunchedEffect(message) {
        if (message != null) {
            delay(2800)
            vm.clearToast()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.padding(bottom = 96.dp, start = 20.dp, end = 20.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0xF2121A2B))
                    .border(1.dp, accent.a.copy(alpha = 0.32f), RoundedCornerShape(15.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(
                    message ?: "",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = TextPrimary,
                )
            }
        }
    }
}
