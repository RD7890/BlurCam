package com.blurcam.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blurcam.app.data.ClipRepo
import com.blurcam.app.data.fmtTenths
import com.blurcam.app.pipeline.BokehParams
import com.blurcam.app.pipeline.Preview
import com.blurcam.app.pipeline.PreviewEngine
import com.blurcam.app.pipeline.RenderManager
import com.blurcam.app.pipeline.RenderParams
import com.blurcam.app.pipeline.RenderState
import com.blurcam.app.ui.components.ApertureRuler
import com.blurcam.app.ui.components.ApertureStops
import com.blurcam.app.ui.components.BcButton
import com.blurcam.app.ui.components.BcText
import com.blurcam.app.ui.components.BtnKind
import com.blurcam.app.ui.components.Chip
import com.blurcam.app.ui.components.ConfirmSheet
import com.blurcam.app.ui.components.FlatSlider
import com.blurcam.app.ui.components.FlatSwitch
import com.blurcam.app.ui.components.IconBtn
import com.blurcam.app.ui.components.Ph
import com.blurcam.app.ui.components.PhIcon
import com.blurcam.app.ui.components.Segmented
import com.blurcam.app.ui.components.Spinner
import com.blurcam.app.ui.components.stopLabel
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.BcType
import com.blurcam.app.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Composable
fun StudioScreen(raw: File, onBack: () -> Unit, onDeleted: () -> Unit, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    val clipId = raw.nameWithoutExtension
    val engine = remember { PreviewEngine() }
    DisposableEffect(Unit) { onDispose { engine.close() } }

    val duration by produceState(0L, raw) { value = withContext(Dispatchers.IO) { ClipRepo.durationOf(raw) } }
    var scrub by remember { mutableFloatStateOf(0.2f) }
    var stopIndex by remember { mutableIntStateOf(2) }
    var bloom by remember { mutableStateOf(true) }
    var quality by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val render by RenderManager.state.collectAsState()
    val hasRendered = remember(render) { ClipRepo.renderedFile(ctx, raw).let { it.exists() && it.length() > 0 } }
    val running = render as? RenderState.Running
    val thisRunning = running != null && running.clip == clipId
    val otherRunning = running != null && running.clip != clipId
    val failed = (render as? RenderState.Failed)?.takeIf { it.clip == clipId }

    // Live still preview, debounced, skipped while a render owns the GPU
    LaunchedEffect(duration) {
        if (duration <= 0L) return@LaunchedEffect
        snapshotFlow { Triple(scrub, stopIndex, bloom) }.collectLatest { (s, idx, b) ->
            delay(90)
            if (RenderManager.state.value is RenderState.Running) return@collectLatest
            busy = true
            val t = (s * duration).toLong().coerceIn(0L, max(0L, duration - 150))
            val p = engine.render(raw.absolutePath, t, BokehParams(ApertureStops[idx], b))
            if (p != null) preview = p
            busy = false
        }
    }

    // Render finished for this clip -> open the result
    LaunchedEffect(render) {
        val r = render
        if (r is RenderState.Done && r.clip == clipId) {
            RenderManager.reset()
            onPlay()
        }
    }

    Box(Modifier.fillMaxSize().background(Bc.Ink)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // top bar
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Ph.ArrowLeft, onBack)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BcText("Studio", BcType.Title)
                    BcText("DEPTH OF FIELD", BcType.Label, color = Bc.TextDim)
                }
                IconBtn(Ph.Trash, { confirmDelete = true }, tint = Bc.Rec)
            }

            // preview
            val frameShape = RoundedCornerShape(22.dp)
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(frameShape).background(Bc.Panel).border(1.dp, Bc.Line, frameShape)
            ) {
                val p = preview
                if (p == null) {
                    Spinner(Bc.Accent, 28.dp, Modifier.align(Alignment.Center))
                } else {
                    Image(p.processed, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    val a by animateFloatAsState(if (compare) 1f else 0f, tween(140), label = "compareAlpha")
                    Image(p.original, null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Fit)

                    AnimatedContent(
                        targetState = if (compare) 2 else if (p.subjectFound) 1 else 0,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "subjectChip"
                    ) { mode ->
                        when (mode) {
                            2 -> Chip("ORIGINAL", dot = Bc.TextDim, fg = Bc.Text)
                            1 -> Chip("SUBJECT LOCKED", dot = Bc.Mint, fg = Bc.Text)
                            else -> Chip("NO PERSON · CENTRE FOCUS", dot = Bc.Amber, fg = Bc.Text)
                        }
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    AnimatedVisibility(
                        visible = busy && preview != null,
                        enter = fadeIn(tween(120)), exit = fadeOut(tween(200))
                    ) { Spinner(Bc.Accent, 22.dp) }
                }

                // hold to compare
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp)
                        .clip(RoundedCornerShape(100.dp)).background(Bc.Ink)
                        .border(1.dp, Bc.Line, RoundedCornerShape(100.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { compare = true; tryAwaitRelease(); compare = false })
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PhIcon(Ph.Eye, Bc.Text, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    BcText("HOLD TO COMPARE", BcType.Label)
                }
            }

            // scrubber
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BcText(fmtTenths((scrub * duration).toLong()), BcType.Time, color = Bc.TextDim)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) { FlatSlider(scrub, { scrub = it }) }
                Spacer(Modifier.width(14.dp))
                BcText(fmtTenths(duration), BcType.Time, color = Bc.TextMute)
            }

            // controls
            val panelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.fillMaxWidth().clip(panelShape).background(Bc.Panel).border(1.dp, Bc.Line, panelShape)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        BcText("APERTURE", BcType.Label, color = Bc.TextDim)
                        BcText("Background blur", BcType.Small, color = Bc.TextMute)
                    }
                    AnimatedContent(
                        targetState = stopIndex,
                        transitionSpec = {
                            val up = targetState > initialState
                            (slideInVertically(Motion.enter()) { if (up) it else -it } + fadeIn(tween(160))) togetherWith
                                (slideOutVertically(Motion.quick()) { if (up) -it else it } + fadeOut(tween(100)))
                        },
                        label = "fValue"
                    ) { idx ->
                        BcText("f/" + stopLabel(ApertureStops[idx]), BcType.Value, color = Bc.Accent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ApertureRuler(stopIndex, { stopIndex = it })
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BcText("Highlight bloom", BcType.Body)
                        BcText("Bright lights become bokeh discs", BcType.Small, color = Bc.TextDim)
                    }
                    FlatSwitch(bloom) { bloom = it }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BcText("Export", BcType.Body, Modifier.weight(1f))
                    Segmented(listOf("720p", "1080p"), quality, { quality = it }, Modifier.width(180.dp))
                }
                Spacer(Modifier.height(18.dp))

                // call to action
                AnimatedVisibility(visible = failed != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp))
                            .background(Bc.PanelHi).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PhIcon(Ph.Warning, Bc.Amber, size = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        BcText(failed?.message ?: "", BcType.Small, maxLines = 2)
                    }
                }
                AnimatedContent(
                    targetState = if (thisRunning) 1 else 0,
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically(Motion.enter()) { it / 3 }) togetherWith fadeOut(tween(120)) },
                    label = "cta"
                ) { mode ->
                    if (mode == 1 && running != null) {
                        RenderProgress(running.progress, running.etaSec) { RenderManager.cancel() }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (hasRendered) {
                                BcButton("View", onPlay, Modifier.weight(1f), kind = BtnKind.Secondary, icon = Ph.Play)
                            }
                            BcButton(
                                if (hasRendered) "Re-render" else "Render cinematic",
                                {
                                    RenderManager.start(
                                        clipId, raw, ClipRepo.renderedFile(ctx, raw),
                                        RenderParams(BokehParams(ApertureStops[stopIndex], bloom), if (quality == 0) 720 else 1080)
                                    )
                                },
                                Modifier.weight(if (hasRendered) 1.4f else 1f),
                                icon = Ph.Sparkle,
                                enabled = !otherRunning
                            )
                        }
                    }
                }
            }
        }

        ConfirmSheet(
            visible = confirmDelete,
            title = "Delete this take?",
            body = "The original and its cinematic render will be removed from your phone.",
            confirmText = "Delete",
            onConfirm = {
                if (thisRunning) RenderManager.cancel()
                ClipRepo.delete(ctx, raw)
                confirmDelete = false
                onDeleted()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun RenderProgress(progress: Float, etaSec: Int, onCancel: () -> Unit) {
    val p by animateFloatAsState(progress, tween(250), label = "renderProgress")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(Bc.Accent, 18.dp)
            Spacer(Modifier.width(10.dp))
            BcText("RENDERING  " + (p * 100).toInt() + "%", BcType.Label)
            Spacer(Modifier.weight(1f))
            if (etaSec >= 0) BcText("~${etaSec}s LEFT", BcType.Label, color = Bc.TextDim)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Bc.Line)) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Bc.Accent))
        }
        Spacer(Modifier.height(14.dp))
        BcButton("Cancel render", onCancel, Modifier.fillMaxWidth(), kind = BtnKind.Secondary, icon = Ph.X)
    }
}
