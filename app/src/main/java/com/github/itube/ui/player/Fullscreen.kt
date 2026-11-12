package com.github.itube

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Stable
class OrientationController(private val activity: ComponentActivity) {
    private enum class Forced { Landscape, Portrait }

    // Detect Android TV.
    private val isTv = activity.packageManager
        .hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private var forced by mutableStateOf<Forced?>(null)
    private var playerOpen = false

    var multiWindow by mutableStateOf(activity.isInMultiWindowMode)
        private set

    private var paneFullscreen by mutableStateOf<Boolean?>(null)
    private var portraitFullscreen by mutableStateOf(false)

    private val listener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val physicallyLandscape = degrees in 60..120 || degrees in 240..300
            val physicallyPortrait = degrees <= 30 || degrees >= 330

            when {
                forced == Forced.Landscape &&
                    physicallyLandscape && autoRotateOn() -> forced = null

                forced == Forced.Portrait &&
                    physicallyPortrait -> forced = null
            }
        }
    }

    private fun autoRotateOn() =
        Settings.System.getInt(
            activity.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1

    fun showsFullscreen(playerOpen: Boolean, landscape: Boolean): Boolean = when {
        !playerOpen -> false
        multiWindow -> !AudioOnly.enabled && (paneFullscreen ?: landscape)
        else -> landscape || (portraitFullscreen && !AudioOnly.enabled)
    }

    fun enterFullscreen(vertical: Boolean) {
        when {
            multiWindow -> paneFullscreen = true
            vertical -> portraitFullscreen = true
            else -> forced = Forced.Landscape
        }
    }

    fun exitFullscreen() {
        when {
            multiWindow -> paneFullscreen = false
            portraitFullscreen -> portraitFullscreen = false
            else -> forced = Forced.Portrait
        }
    }

    fun setPlayerOpen(open: Boolean) {
        playerOpen = open
        if (!open) {
            forced = null
            paneFullscreen = null
            portraitFullscreen = false
        }
        apply()
    }

    fun apply() {
        activity.requestedOrientation = when {
            // TV always remains landscape, regardless of the active screen.
            isTv -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

            multiWindow -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            !playerOpen -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            forced == Forced.Landscape ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            forced == Forced.Portrait ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            else -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    @Composable
    fun Bind(playerOpen: Boolean, landscape: Boolean) {
        DisposableEffect(Unit) {
            if (!isTv && listener.canDetectOrientation()) {
                listener.enable()
            }

            val windowListener = Consumer<MultiWindowModeChangedInfo> { info ->
                multiWindow = info.isInMultiWindowMode
                forced = null
            }

            activity.addOnMultiWindowModeChangedListener(windowListener)

            onDispose {
                listener.disable()
                activity.removeOnMultiWindowModeChangedListener(windowListener)
            }
        }

        LaunchedEffect(landscape, multiWindow) {
            paneFullscreen = null
            if (landscape || multiWindow) {
                portraitFullscreen = false
            }
        }

        LaunchedEffect(playerOpen, forced, multiWindow) {
            setPlayerOpen(playerOpen)
        }
    }
}

@Composable
fun rememberOrientationController(activity: ComponentActivity) =
    remember(activity) { OrientationController(activity) }

/** Hides the status and navigation bars while [hidden]. */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current

    DisposableEffect(hidden) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)

        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Picture-in-picture controller. */
@Stable
class PipController(private val activity: ComponentActivity) {
    var active by mutableStateOf(activity.isInPictureInPictureMode)
        private set

    val supported = activity.packageManager.hasSystemFeature(
        PackageManager.FEATURE_PICTURE_IN_PICTURE
    )

    var videoBounds: Rect? = null

    fun enter(videoAspect: Float): Boolean {
        if (!supported) return false

        val ratio = videoAspect.coerceIn(0.42f, 2.38f)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .apply {
                videoBounds?.let { b ->
                    setSourceRectHint(
                        android.graphics.Rect(
                            b.left.toInt(),
                            b.top.toInt(),
                            b.right.toInt(),
                            b.bottom.toInt()
                        )
                    )
                }
            }
            .build()

        return activity.enterPictureInPictureMode(params)
    }

    @Composable
    fun Bind(onClosed: () -> Unit) {
        DisposableEffect(activity) {
            val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
                active = info.isInPictureInPictureMode

                if (
                    !info.isInPictureInPictureMode &&
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    onClosed()
                }
            }

            activity.addOnPictureInPictureModeChangedListener(listener)

            onDispose {
                activity.removeOnPictureInPictureModeChangedListener(listener)
            }
        }
    }
}

val LocalPictureInPicture =
    staticCompositionLocalOf<PipController?> { null }
    
