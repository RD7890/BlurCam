package com.blurcam.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.blurcam.app.ui.components.BcText
import com.blurcam.app.ui.components.Ph
import com.blurcam.app.ui.components.PhIcon
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.BcType
import com.blurcam.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val rot = remember { Animatable(-140f) }
    val scale = remember { Animatable(0.35f) }
    val textAlpha = remember { Animatable(0f) }
    val textShift = remember { Animatable(18f) }

    LaunchedEffect(Unit) {
        launch { rot.animateTo(0f, tween(950, easing = Motion.ExpoOut)) }
        launch { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 200f)) }
        delay(380)
        launch { textAlpha.animateTo(1f, tween(480)) }
        launch { textShift.animateTo(0f, tween(620, easing = Motion.ExpoOut)) }
        delay(900)
        done()
    }

    Column(
        Modifier.fillMaxSize().background(Bc.Ink),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PhIcon(
            Ph.ApertureFill, Bc.Accent,
            Modifier.graphicsLayer { rotationZ = rot.value; scaleX = scale.value; scaleY = scale.value },
            size = 92.dp
        )
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.graphicsLayer { alpha = textAlpha.value; translationY = textShift.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BcText("BlurCam", BcType.Display)
            Spacer(Modifier.height(8.dp))
            BcText("CINEMATIC DEPTH · AFTER THE SHOT", BcType.Label, color = Bc.TextDim)
        }
    }
}
