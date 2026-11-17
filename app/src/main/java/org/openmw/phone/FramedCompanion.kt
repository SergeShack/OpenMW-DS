package org.openmw.phone

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The companion on the phone's screen, for the Phone + external screen profile.
 *
 * The companion is laid out for the dual-screen handhelds' bottom panels (the Thor's is
 * 1240 x 1080). A phone held in landscape is about 2.2:1, and filling it stretches every panel
 * sideways. On a screen wider than [WIDE_ASPECT] the content is centred at [FRAMED_ASPECT], and the
 * touch controller, or with a real controller connected the player's key buttons, take the two
 * sides ([CompanionSidePad]). With a real controller and no key buttons the sides would be empty,
 * and the companion takes nearly the whole width: a narrow strip is left at each side
 * ([CompanionSidePad.EDGE_DP]), for the edit button and the camera cutout.
 *
 * The frame is a View, not a Compose box, so the companion's composition root is the framed area:
 * popups placed from `boundsInRoot` land where they should. [FramedLocals] reports the frame's size
 * where the companion reads the window or configuration size.
 */
object FramedCompanion {

    /** Screens wider than this get the framed layout. */
    private const val WIDE_ASPECT = 1.6f

    /** Aspect of the framed companion. */
    private const val FRAMED_ASPECT = 4f / 3f

    /**
     * Width of the framed companion in pixels, or 0 when it fills the screen. Used for the
     * pause/options window, which is a separate window over the companion.
     */
    @Volatile
    var framedWidthPx: Int = 0
        private set

    /** Set the companion host activity's content: the framed companion and the side controller. */
    fun install(activity: ComponentActivity, visible: StateFlow<Boolean>, content: @Composable () -> Unit) {
        val frameSize = mutableStateOf(IntSize.Zero)
        val sideWidth = mutableIntStateOf(0)
        // Width of the frame, 0 when the screen is not wide enough for one.
        var frameWidth = 0
        // The sides have nothing to show, and the companion takes nearly the whole width.
        var sidesEmpty = false
        val edgePx = (CompanionSidePad.EDGE_DP * activity.resources.displayMetrics.density).roundToInt()
        val companionView = ComposeView(activity).apply {
            setContent {
                val shown by visible.collectAsState()
                FramedLocals(frameSize.value) { if (shown) content() }
            }
        }
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            companionView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        fun place() {
            val framed = frameWidth > 0
            val inner = when {
                !framed -> 0
                sidesEmpty -> maxOf(frameWidth, root.width - 2 * edgePx)
                else -> frameWidth
            }
            frameSize.value = if (framed) IntSize(inner, root.height) else IntSize.Zero
            framedWidthPx = inner
            if (framed) drawUnderCutout(activity)
            val width = if (framed) inner else ViewGroup.LayoutParams.MATCH_PARENT
            // A layout request made during layout is dropped; post it.
            root.post { setWidth(companionView, width) }
        }
        root.addView(
            CompanionSidePad.view(activity, visible, sideWidth) { empty ->
                if (sidesEmpty != empty) {
                    sidesEmpty = empty
                    place()
                }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val framed = w.toFloat() / h > WIDE_ASPECT
            frameWidth = if (framed) (h * FRAMED_ASPECT).roundToInt() else 0
            // The sides keep their width while the companion takes their room, for the edit button
            // and for the key buttons once there are some.
            sideWidth.intValue = if (framed) (w - frameWidth) / 2 else 0
            place()
        }
        activity.setContentView(root)
    }

    /** The companion host is going away: let go of the touch controller. */
    fun onCompanionDestroyed() {
        VirtualPad.remove()
        framedWidthPx = 0
    }

    /** Keep the pause/options window inside the framed companion. */
    fun fitOverlay(lp: WindowManager.LayoutParams) {
        val width = framedWidthPx
        if (width <= 0) return
        lp.width = width
        lp.gravity = Gravity.CENTER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** With the content centred, the camera cutout only covers black, so the window may use it.
     *  Without this the cutout side is inset and the content sits off-centre. */
    private fun drawUnderCutout(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val window = activity.window
        val mode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (window.attributes.layoutInDisplayCutoutMode == mode) return
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
    }

    private fun setWidth(view: View, width: Int) {
        val lp = view.layoutParams
        if (lp.width == width) return
        lp.width = width
        view.layoutParams = lp
    }
}

/**
 * The window and configuration sizes, as seen by the framed companion: the frame's size while it
 * is narrower than the window ([size] non-zero), the real ones otherwise. Always provided, so a
 * change of frame never changes the composition's shape and the companion keeps its state.
 */
@Composable
private fun FramedLocals(size: IntSize, content: @Composable () -> Unit) {
    val baseInfo = LocalWindowInfo.current
    val baseConfig = LocalConfiguration.current
    val density = LocalDensity.current.density
    val info = remember(baseInfo, size) {
        if (size == IntSize.Zero) baseInfo else object : WindowInfo {
            override val isWindowFocused: Boolean get() = baseInfo.isWindowFocused
            override val containerSize: IntSize get() = size
        }
    }
    val config = remember(baseConfig, size, density) {
        if (size == IntSize.Zero) baseConfig else Configuration(baseConfig).apply {
            screenWidthDp = (size.width / density).toInt()
            screenHeightDp = (size.height / density).toInt()
        }
    }
    CompositionLocalProvider(
        LocalWindowInfo provides info,
        LocalConfiguration provides config,
        content = content
    )
}
