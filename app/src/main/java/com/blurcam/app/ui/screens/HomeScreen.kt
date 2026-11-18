package com.blurcam.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blurcam.app.data.Clip
import com.blurcam.app.data.ClipRepo
import com.blurcam.app.data.fmtClock
import com.blurcam.app.ui.components.BcButton
import com.blurcam.app.ui.components.BcText
import com.blurcam.app.ui.components.Chip
import com.blurcam.app.ui.components.Ph
import com.blurcam.app.ui.components.PhIcon
import com.blurcam.app.ui.components.pressable
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.BcType
import com.blurcam.app.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(onRecord: () -> Unit, onOpen: (File) -> Unit) {
    val ctx = LocalContext.current
    var clips by remember { mutableStateOf<List<Clip>?>(null) }
    LaunchedEffect(Unit) { clips = withContext(Dispatchers.IO) { ClipRepo.list(ctx) } }

    Box(Modifier.fillMaxSize().background(Bc.Ink)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(count = clips?.size ?: 0)
            val list = clips
            when {
                list == null -> Spacer(Modifier.weight(1f))
                list.isEmpty() -> EmptyState(Modifier.weight(1f))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 128.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { index, clip ->
                        ClipCard(clip, index) { onOpen(clip.raw) }
                    }
                }
            }
        }

        // Bottom action bar (solid, flat)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Bc.Ink)
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            BcButton("Record a take", onRecord, Modifier.fillMaxWidth(), icon = Ph.VideoCamera)
        }
    }
}

@Composable
private fun Header(count: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.ApertureFill, Bc.Accent, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            BcText("BlurCam", BcType.Title)
            Spacer(Modifier.weight(1f))
            Chip(if (count == 1) "1 TAKE" else "$count TAKES", bg = Bc.Panel)
        }
        Spacer(Modifier.height(20.dp))
        BcText("Shoot sharp.", BcType.Display)
        BcText("Blur later.", BcType.Display, color = Bc.Accent)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "emptySpin")
    val r by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "emptyAngle"
    )
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PhIcon(Ph.Aperture, Bc.Line, Modifier.graphicsLayer { rotationZ = r }, size = 120.dp)
        Spacer(Modifier.height(24.dp))
        BcText("No takes yet", BcType.Title)
        Spacer(Modifier.height(8.dp))
        BcText(
            "Record a clip with a person in frame. BlurCam keeps them tack-sharp and melts the background into cinematic bokeh.",
            BcType.Body, color = Bc.TextDim, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ClipCard(clip: Clip, index: Int, onClick: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 60L)
        enter.animateTo(1f, tween(520, easing = Motion.ExpoOut))
    }
    val thumb by produceState<ImageBitmap?>(null, clip.raw.path) {
        value = withContext(Dispatchers.IO) { ClipRepo.thumbnail(clip.raw)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(20.dp)

    Column(
        Modifier
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 48f }
            .pressable(scaleTo = 0.96f, onClick = onClick)
            .clip(shape)
            .background(Bc.Panel)
            .border(1.dp, Bc.Line, shape)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Bc.PanelHi)) {
            val img = thumb
            if (img != null) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                PhIcon(Ph.FilmSlate, Bc.TextMute, Modifier.align(Alignment.Center), size = 32.dp)
            }
            Chip(
                if (clip.rendered != null) "CINEMATIC" else "RAW",
                Modifier.align(Alignment.TopStart).padding(10.dp),
                dot = if (clip.rendered != null) Bc.Mint else Bc.TextMute,
                fg = Bc.Text
            )
            Chip(
                fmtClock(clip.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                fg = Bc.Text
            )
        }
        Column(Modifier.padding(14.dp)) {
            BcText(clip.title, BcType.Body, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            BcText(clip.subtitle, BcType.Label, color = Bc.TextDim, maxLines = 1)
        }
    }
}
