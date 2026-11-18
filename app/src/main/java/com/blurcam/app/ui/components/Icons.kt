package com.blurcam.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blurcam.app.R

/** Phosphor Icons (MIT) vendored as vector drawables. https://phosphoricons.com */
object Ph {
    val Aperture = R.drawable.ph_aperture_bold
    val ApertureFill = R.drawable.ph_aperture_fill
    val CameraRotate = R.drawable.ph_camera_rotate_bold
    val X = R.drawable.ph_x_bold
    val Trash = R.drawable.ph_trash_bold
    val Export = R.drawable.ph_export_bold
    val Download = R.drawable.ph_download_simple_bold
    val ArrowLeft = R.drawable.ph_arrow_left_bold
    val Play = R.drawable.ph_play_fill
    val Pause = R.drawable.ph_pause_fill
    val Stop = R.drawable.ph_stop_fill
    val Check = R.drawable.ph_check_bold
    val Notch = R.drawable.ph_circle_notch_bold
    val Eye = R.drawable.ph_eye_bold
    val Warning = R.drawable.ph_warning_circle_bold
    val FilmSlate = R.drawable.ph_film_slate_bold
    val VideoCamera = R.drawable.ph_video_camera_fill
    val UserFocus = R.drawable.ph_user_focus_bold
    val Sliders = R.drawable.ph_sliders_horizontal_bold
    val Sparkle = R.drawable.ph_sparkle_fill
    val Refresh = R.drawable.ph_arrow_clockwise_bold
    val Mic = R.drawable.ph_microphone_bold
    val CameraFill = R.drawable.ph_camera_fill
    val CheckCircle = R.drawable.ph_check_circle_fill
    val Bolt = R.drawable.ph_lightning_fill
}

@Composable
fun PhIcon(
    @DrawableRes id: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Icon(painter = painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}
