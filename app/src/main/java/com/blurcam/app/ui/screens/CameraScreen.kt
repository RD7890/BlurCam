package com.blurcam.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blurcam.app.data.ClipRepo
import com.blurcam.app.data.fmtTenths
import com.blurcam.app.ui.components.BcButton
import com.blurcam.app.ui.components.BcText
import com.blurcam.app.ui.components.BtnKind
import com.blurcam.app.ui.components.Chip
import com.blurcam.app.ui.components.IconBtn
import com.blurcam.app.ui.components.Ph
import com.blurcam.app.ui.components.PhIcon
import com.blurcam.app.ui.components.PulseDot
import com.blurcam.app.ui.components.pressable
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.BcType
import com.blurcam.app.ui.theme.Motion
import java.io.File

private const val MAX_MS = 30_000L
private val NEEDED = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

private fun hasPermissions(ctx: Context) =
    NEEDED.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private class RecState {
    var recording: Recording? = null
    var discard = false
    var file: File? = null
}

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(onClose: () -> Unit, onRecorded: (File) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(hasPermissions(ctx)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(ctx); asked = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(NEEDED) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasPermissions(ctx) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Bc.Ink)) {
        if (!granted) {
            PermissionPanel(
                asked = asked,
                onClose = onClose,
                onAllow = { launcher.launch(NEEDED) },
                onSettings = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        } else {
            LiveCamera(onClose, onRecorded)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LiveCamera(onClose: () -> Unit, onRecorded: (File) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { RecState() }

    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var front by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val camController = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.VIDEO_CAPTURE)
            videoCaptureQualitySelector = QualitySelector.from(
                Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )
        }
    }
    DisposableEffect(lifecycleOwner) {
        camController.bindToLifecycle(lifecycleOwner)
        onDispose { camController.unbind() }
    }
    LaunchedEffect(front) {
        camController.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun stop() { state.recording?.stop() }

    fun start() {
        error = null
        val file = ClipRepo.newRawFile(ctx)
        state.file = file
        state.discard = false
        elapsedMs = 0
        state.recording = camController.startRecording(
            FileOutputOptions.Builder(file).build(),
            AudioConfig.create(true),
            ContextCompat.getMainExecutor(ctx)
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Status -> {
                    elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    if (elapsedMs >= MAX_MS) stop()
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    state.recording = null
                    val f = state.file
                    if (state.discard || event.hasError()) {
                        f?.delete()
                        if (!state.discard) error = "That take was too short. Hold the record button a little longer."
                    } else if (f != null) {
                        onRecorded(f)
                    }
                }
                else -> Unit
            }
        }
    }

    fun leave() {
        if (state.recording != null) { state.discard = true; stop() }
        onClose()
    }

    BackHandler { leave() }
    DisposableEffect(Unit) { onDispose { if (state.recording != null) { state.discard = true; state.recording?.stop() } } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // top bar
        Box(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp)) {
            IconBtn(Ph.X, { leave() }, Modifier.align(Alignment.CenterStart))
            AnimatedContent(
                targetState = isRecording,
                modifier = Modifier.align(Alignment.Center),
                transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.9f)) },
                label = "topPill"
            ) { rec ->
                if (rec) {
                    Row(
                        Modifier.clip(RoundedCornerShape(100.dp)).background(Bc.Panel)
                            .border(1.dp, Bc.Line, RoundedCornerShape(100.dp))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulseDot(Bc.Rec)
                        Spacer(Modifier.width(10.dp))
                        BcText(fmtTenths(elapsedMs), BcType.Time)
                    }
                } else {
                    Chip("READY", dot = Bc.Mint, bg = Bc.Panel)
                }
            }
        }

        // viewfinder (9:16, what you see is what you get)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(26.dp)
            Box(
                Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                    .clip(shape).background(Bc.Panel).border(1.dp, Bc.Line, shape)
            ) {
                AndroidView(
                    factory = { c ->
                        PreviewView(c).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            this.controller = camController
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                ViewfinderOverlay(
                    isRecording = isRecording,
                    error = error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp)
                )
            }
        }

        // controls
        Box(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 24.dp)) {
            Column(Modifier.align(Alignment.CenterStart)) {
                BcText("LIMIT", BcType.Label, color = Bc.TextMute)
                BcText("0:30", BcType.Time, color = Bc.TextDim)
            }
            RecordButton(
                recording = isRecording,
                progress = (elapsedMs / MAX_MS.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.align(Alignment.Center),
                onClick = { if (isRecording) stop() else start() }
            )
            val flip by animateFloatAsState(if (front) 180f else 0f, spring(0.7f, 260f), label = "flip")
            IconBtn(
                Ph.CameraRotate, { if (!isRecording) front = !front },
                Modifier.align(Alignment.CenterEnd).graphicsLayer { rotationY = flip },
                tint = if (isRecording) Bc.TextMute else Bc.Text, size = 52.dp
            )
        }
    }
}

@Composable
private fun ViewfinderOverlay(isRecording: Boolean, error: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        AnimatedVisibility(
            visible = !isRecording && error == null,
            enter = fadeIn(tween(300)) + slideInVertically(tween(400)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(100.dp)).background(Bc.Ink)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhIcon(Ph.UserFocus, Bc.Accent, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                BcText("Keep your subject in frame", BcType.Small)
            }
        }
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(300)) { it / 2 },
            exit = fadeOut(tween(150)),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Bc.Ink).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhIcon(Ph.Warning, Bc.Amber, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                BcText(error ?: "", BcType.Small)
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val inner: Dp by animateDpAsState(if (recording) 30.dp else 62.dp, spring(0.65f, 380f), label = "recInner")
    val corner: Dp by animateDpAsState(if (recording) 8.dp else 31.dp, spring(0.65f, 380f), label = "recCorner")
    val ring by animateColorAsState(if (recording) Bc.Accent else Bc.Text, tween(250), label = "recRing")
    val track by animateColorAsState(if (recording) Bc.Line else Bc.Ink, tween(250), label = "recTrack")
    val prog by animateFloatAsState(progress, tween(120), label = "recProg")

    Box(
        modifier.pressable(scaleTo = 0.92f, onClick = onClick).size(88.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            val d = Size(size.width - sw, size.height - sw)
            val tl = Offset(sw / 2, sw / 2)
            if (recording) {
                drawArc(track, 0f, 360f, false, tl, d, style = Stroke(sw))
                drawArc(ring, -90f, 360f * prog, false, tl, d, style = Stroke(sw, cap = StrokeCap.Round))
            } else {
                drawArc(ring, 0f, 360f, false, tl, d, style = Stroke(sw))
            }
        }
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(Bc.Rec))
    }
}

@Composable
private fun PermissionPanel(asked: Boolean, onClose: () -> Unit, onAllow: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        IconBtn(Ph.X, onClose)
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Bc.Panel).border(1.dp, Bc.Line, CircleShape), contentAlignment = Alignment.Center) {
                PhIcon(Ph.CameraFill, Bc.Accent, size = 42.dp)
            }
            Spacer(Modifier.height(24.dp))
            BcText("Camera & microphone", BcType.Title)
            Spacer(Modifier.height(8.dp))
            BcText(
                "BlurCam records clean, sharp footage with sound. Everything stays on your phone.",
                BcType.Body, color = Bc.TextDim, textAlign = TextAlign.Center
            )
        }
        BcButton("Allow access", onAllow, Modifier.fillMaxWidth(), icon = Ph.Check)
        AnimatedVisibility(visible = asked) {
            Column {
                Spacer(Modifier.height(12.dp))
                BcButton("Open app settings", onSettings, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
            }
        }
    }
}
