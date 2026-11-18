package com.blurcam.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.blurcam.app.pipeline.RenderManager
import com.blurcam.app.pipeline.RenderState
import com.blurcam.app.ui.screens.CameraScreen
import com.blurcam.app.ui.screens.HomeScreen
import com.blurcam.app.ui.screens.PlayerScreen
import com.blurcam.app.ui.screens.SplashScreen
import com.blurcam.app.ui.screens.StudioScreen
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.Motion
import java.io.File

sealed class Screen(val depth: Int) {
    data object Splash : Screen(0)
    data object Home : Screen(1)
    data object Camera : Screen(2)
    data class Studio(val path: String) : Screen(2)
    data class Player(val path: String) : Screen(3)
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }

    // keep the display awake while a render is running
    val render by RenderManager.state.collectAsState()
    val view = LocalView.current
    SideEffect { view.keepScreenOn = render is RenderState.Running }

    BackHandler(enabled = screen !is Screen.Splash && screen !is Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Player -> Screen.Studio(s.path)
            else -> Screen.Home
        }
    }

    AnimatedContent(
        targetState = screen,
        modifier = Modifier.fillMaxSize().background(Bc.Ink),
        transitionSpec = {
            val from = initialState
            val to = targetState
            when {
                from is Screen.Splash ->
                    fadeIn(tween(500)) togetherWith fadeOut(tween(250))
                to is Screen.Camera ->
                    (slideInVertically(tween(480, easing = Motion.ExpoOut)) { it / 5 } + fadeIn(tween(300))) togetherWith
                        fadeOut(tween(200))
                from is Screen.Camera && to is Screen.Home ->
                    fadeIn(tween(300)) togetherWith
                        (slideOutVertically(tween(320, easing = Motion.Standard)) { it / 6 } + fadeOut(tween(220)))
                to.depth >= from.depth ->
                    (slideInHorizontally(tween(420, easing = Motion.ExpoOut)) { it / 6 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(320, easing = Motion.Standard)) { -it / 10 } + fadeOut(tween(200)))
                else ->
                    (slideInHorizontally(tween(420, easing = Motion.ExpoOut)) { -it / 6 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(320, easing = Motion.Standard)) { it / 10 } + fadeOut(tween(200)))
            }
        },
        label = "nav"
    ) { s ->
        when (s) {
            Screen.Splash -> SplashScreen { screen = Screen.Home }
            Screen.Home -> HomeScreen(
                onRecord = { screen = Screen.Camera },
                onOpen = { screen = Screen.Studio(it.absolutePath) }
            )
            Screen.Camera -> CameraScreen(
                onClose = { screen = Screen.Home },
                onRecorded = { screen = Screen.Studio(it.absolutePath) }
            )
            is Screen.Studio -> StudioScreen(
                raw = File(s.path),
                onBack = { screen = Screen.Home },
                onDeleted = { screen = Screen.Home },
                onPlay = { screen = Screen.Player(s.path) }
            )
            is Screen.Player -> PlayerScreen(
                raw = File(s.path),
                onBack = { screen = Screen.Studio(s.path) }
            )
        }
    }
}
