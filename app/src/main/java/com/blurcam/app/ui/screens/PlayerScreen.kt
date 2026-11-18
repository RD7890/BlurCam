package com.blurcam.app.ui.screens

import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blurcam.app.data.ClipRepo
import com.blurcam.app.data.fmtTenths
import com.blurcam.app.ui.components.BcButton
import com.blurcam.app.ui.components.BcText
import com.blurcam.app.ui.components.BtnKind
import com.blurcam.app.ui.components.Chip
import com.blurcam.app.ui.components.FlatSlider
import com.blurcam.app.ui.components.FlatToast
import com.blurcam.app.ui.components.IconBtn
import com.blurcam.app.ui.components.Ph
import com.blurcam.app.ui.components.PhIcon
import com.blurcam.app.ui.components.pressable
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.BcType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PlayerScreen(raw: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(raw) { ClipRepo.renderedFile(ctx, raw) }

    var video by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(true) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekFrac by remember { mutableFloatStateOf(0f) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(video) {
        while (isActive) {
            val v = video
            if (v != null && !seeking && durMs > 0) posMs = v.currentPosition.toLong()
            delay(60)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) { delay(2200); toast = null }
    }
    DisposableEffect(Unit) { onDispose { video?.stopPlayback() } }

    Box(Modifier.fillMaxSize().background(Bc.Ink)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Ph.ArrowLeft, onBack)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BcText("Result", BcType.Title)
                    BcText("CINEMATIC RENDER", BcType.Label, color = Bc.TextDim)
                }
                Chip("DEPTH OF FIELD", dot = Bc.Mint, bg = Bc.Panel)
            }

            val frame = RoundedCornerShape(22.dp)
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(frame).background(Bc.Panel).border(1.dp, Bc.Line, frame)
                    .pressable(scaleTo = 0.995f) {
                        val v = video
                        if (v != null) {
                            if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { c ->
                        val vv = VideoView(c)
                        vv.setVideoURI(Uri.fromFile(file))
                        vv.setOnPreparedListener { mp ->
                            mp.isLooping = true
                            durMs = vv.duration.toLong()
                            vv.start()
                        }
                        video = vv
                        FrameLayout(c).apply {
                            addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 1.3f)) },
                    label = "playGlyph"
                ) { isPlaying ->
                    if (!isPlaying) {
                        Box(Modifier.size(84.dp).clip(CircleShape).background(Bc.Accent), contentAlignment = Alignment.Center) {
                            PhIcon(Ph.Play, Bc.Ink, size = 36.dp)
                        }
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BcText(fmtTenths(posMs), BcType.Time, color = Bc.TextDim)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) {
                    FlatSlider(
                        value = if (seeking) seekFrac else if (durMs > 0) posMs / durMs.toFloat() else 0f,
                        onValueChange = {
                            seeking = true
                            seekFrac = it
                            posMs = (it * durMs).toLong()
                            video?.seekTo(posMs.toInt())
                        },
                        onFinished = { seeking = false }
                    )
                }
                Spacer(Modifier.width(14.dp))
                BcText(fmtTenths(durMs), BcType.Time, color = Bc.TextMute)
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BcButton(
                    "Save", {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { ClipRepo.saveToGallery(ctx, file) }
                            toast = if (ok) "Saved to Movies/BlurCam" else "Could not save the clip"
                        }
                    },
                    Modifier.weight(1f), kind = BtnKind.Mint, icon = Ph.Download
                )
                BcButton("Share", { ClipRepo.share(ctx, file) }, Modifier.weight(1f), kind = BtnKind.Secondary, icon = Ph.Export)
            }
        }
        FlatToast(toast)
    }
}
