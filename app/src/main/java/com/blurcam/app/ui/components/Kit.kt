package com.blurcam.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.blurcam.app.ui.theme.Bc
import com.blurcam.app.ui.theme.BcType
import com.blurcam.app.ui.theme.Motion
import kotlin.math.roundToInt

/* ───────────────────────── text ───────────────────────── */

@Composable
fun BcText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Bc.Text,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier,
        maxLines = maxLines, textAlign = textAlign, overflow = TextOverflow.Ellipsis
    )
}

/* ───────────────────────── press feedback ───────────────────────── */

/** Springy scale-on-press with no ripple. Put it FIRST in the modifier chain so the whole element scales. */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.95f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleTo else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "press"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/* ───────────────────────── buttons ───────────────────────── */

enum class BtnKind { Primary, Secondary, Mint, Danger }

@Composable
fun BcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    icon: Int? = null,
    enabled: Boolean = true,
) {
    val bg = when (kind) {
        BtnKind.Primary -> Bc.Accent
        BtnKind.Secondary -> Bc.PanelHi
        BtnKind.Mint -> Bc.Mint
        BtnKind.Danger -> Bc.Rec
    }
    val fg = if (kind == BtnKind.Secondary) Bc.Text else Bc.Ink
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .pressable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .height(56.dp)
            .clip(shape)
            .background(bg)
            .then(if (kind == BtnKind.Secondary) Modifier.border(1.dp, Bc.Line, shape) else Modifier)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            PhIcon(icon, fg, size = 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        BcText(text, BcType.Button, color = fg, maxLines = 1)
    }
}

@Composable
fun IconBtn(
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Bc.Text,
    size: Dp = 44.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .pressable(scaleTo = 0.9f, onClick = onClick)
            .size(size)
            .clip(shape)
            .background(Bc.PanelHi)
            .border(1.dp, Bc.Line, shape),
        contentAlignment = Alignment.Center
    ) {
        PhIcon(icon, tint, size = 22.dp)
    }
}

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    fg: Color = Bc.TextDim,
    bg: Color = Bc.Ink,
) {
    val shape = RoundedCornerShape(100.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, Bc.Line, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        BcText(text, BcType.Label, color = fg, maxLines = 1)
    }
}

/* ───────────────────────── ambient motion ───────────────────────── */

@Composable
fun PulseDot(color: Color, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Box(Modifier.size(size).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

@Composable
fun Spinner(tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val r by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spinAngle"
    )
    PhIcon(Ph.Notch, tint, modifier.graphicsLayer { rotationZ = r }, size)
}

/* ───────────────────────── slider / switch / segmented ───────────────────────── */

@Composable
fun FlatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    val thumb by animateDpAsState(if (dragging) 20.dp else 12.dp, spring(0.6f, 500f), label = "thumb")
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onFinished)
    val v = value.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { change((it.x / width).coerceIn(0f, 1f)); finish() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; change((it.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; finish() },
                    onDragCancel = { dragging = false },
                ) { pointer, _ ->
                    pointer.consume()
                    change((pointer.position.x / width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Bc.Line))
        Box(Modifier.fillMaxWidth(v).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Bc.Accent))
        Box(
            Modifier
                .offset { IntOffset(((width - thumb.toPx()) * v).roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(Bc.Text)
        )
    }
}

@Composable
fun FlatSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(0.6f, 500f), label = "swX")
    val bg by animateColorAsState(if (checked) Bc.Accent else Bc.Line, tween(200), label = "swBg")
    val knob by animateColorAsState(if (checked) Bc.Ink else Bc.TextDim, tween(200), label = "swKnob")
    Box(
        Modifier
            .pressable(scaleTo = 0.94f) { onChange(!checked) }
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(bg)
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape).background(knob))
    }
}

@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outer = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier
            .height(44.dp)
            .clip(outer)
            .background(Bc.PanelHi)
            .border(1.dp, Bc.Line, outer)
            .padding(3.dp)
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * selected, spring(0.8f, 420f), label = "segX")
        Box(
            Modifier.offset(x = x).width(segW).fillMaxHeight()
                .clip(RoundedCornerShape(9.dp)).background(Bc.Accent)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val c by animateColorAsState(if (i == selected) Bc.Ink else Bc.TextDim, tween(200), label = "segText")
                Box(
                    Modifier.weight(1f).fillMaxHeight().pressable(scaleTo = 0.97f) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) { BcText(label, BcType.Small, color = c, maxLines = 1) }
            }
        }
    }
}

/* ───────────────────────── aperture ruler (lens ring) ───────────────────────── */

val ApertureStops = listOf(1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)

fun stopLabel(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

@Composable
fun ApertureRuler(index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val n = ApertureStops.size
    var widthPx by remember { mutableIntStateOf(1) }
    val current by rememberUpdatedState(index)
    val onPick by rememberUpdatedState(onIndex)
    val pos by animateFloatAsState((index + 0.5f) / n, spring(0.75f, 380f), label = "rulerPos")

    fun pick(x: Float) {
        val i = ((x / widthPx) * n).toInt().coerceIn(0, n - 1)
        if (i != current) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPick(i)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { pick(it.x) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { pointer, _ ->
                    pointer.consume(); pick(pointer.position.x)
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val w = size.width
            val step = w / n
            val base = size.height
            for (i in 0 until n) {
                val cx = (i + 0.5f) * step
                drawLine(
                    color = if (i == index) Bc.Accent else Bc.TextMute,
                    start = Offset(cx, base - 18.dp.toPx()), end = Offset(cx, base),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
                if (i < n - 1) {
                    for (k in 1..3) {
                        val mx = cx + step * k / 4f
                        drawLine(
                            color = Bc.Line,
                            start = Offset(mx, base - 9.dp.toPx()), end = Offset(mx, base),
                            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
                        )
                    }
                }
            }
            drawRoundRect(
                color = Bc.Accent,
                topLeft = Offset(pos * w - 2.dp.toPx(), 0f),
                size = Size(4.dp.toPx(), base),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            ApertureStops.forEachIndexed { i, f ->
                val c by animateColorAsState(if (i == index) Bc.Text else Bc.TextMute, tween(200), label = "stopLabel")
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    BcText(stopLabel(f), BcType.Label, color = c, maxLines = 1)
                }
            }
        }
    }
}

/* ───────────────────────── overlays ───────────────────────── */

@Composable
fun BoxScope.ConfirmSheet(
    visible: Boolean,
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        Box(
            Modifier.fillMaxSize().background(Bc.Ink.copy(alpha = 0.8f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(Motion.enter()) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(Motion.quick()) { it } + fadeOut(tween(160)),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Bc.Panel)
                .border(1.dp, Bc.Line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            BcText(title, BcType.Title)
            Spacer(Modifier.height(8.dp))
            BcText(body, BcType.Body, color = Bc.TextDim)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BcButton("Cancel", onDismiss, Modifier.weight(1f), kind = BtnKind.Secondary)
                BcButton(confirmText, onConfirm, Modifier.weight(1f), kind = BtnKind.Danger)
            }
        }
    }
}

@Composable
fun BoxScope.FlatToast(message: String?) {
    var last by remember { mutableStateOf("") }
    if (message != null && message != last) last = message
    AnimatedVisibility(
        visible = message != null,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 112.dp),
        enter = slideInVertically(Motion.enter()) { it / 2 } + fadeIn(tween(200)),
        exit = slideOutVertically(Motion.quick()) { it / 2 } + fadeOut(tween(160)),
    ) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.clip(shape).background(Bc.PanelHi).border(1.dp, Bc.Line, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhIcon(Ph.CheckCircle, Bc.Mint, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            BcText(last, BcType.Small)
        }
    }
}
