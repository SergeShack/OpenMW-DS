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
 * touch controller takes the two sides.
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
        val padLeft = sidePadView(activity, visible, Gravity.START) { SidePadLeft() }
        val padRight = sidePadView(activity, visible, Gravity.END) { SidePadRight() }
        root.addView(padLeft)
        root.addView(padRight)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val framed = w.toFloat() / h > WIDE_ASPECT
            val width = if (framed) (h * FRAMED_ASPECT).roundToInt() else ViewGroup.LayoutParams.MATCH_PARENT
            frameSize.value = if (framed) IntSize(width, h) else IntSize.Zero
            framedWidthPx = if (framed) width else 0
            if (framed) drawUnderCutout(activity)
            val side = if (framed) (w - width) / 2 else 0
            // A layout request made during layout is dropped; post it.
            root.post {
                setWidth(companionView, width)
                for (pad in listOf(padLeft, padRight)) {
                    setWidth(pad, side)
                    pad.visibility = if (side > 0) View.VISIBLE else View.GONE
                }
            }
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

    private fun sidePadView(
        activity: ComponentActivity,
        visible: StateFlow<Boolean>,
        gravity: Int,
        content: @Composable () -> Unit,
    ): ComposeView = ComposeView(activity).apply {
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, gravity)
        setContent {
            val shown by visible.collectAsState()
            if (shown) content()
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
