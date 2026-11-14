package org.openmw.phone

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.libsdl.app.SDLActivity

/**
 * The touch controller over the game itself, for the Phone + external screen profile with no
 * external screen connected. The phone is then the only screen, so the same controller the
 * companion has beside it sits at the two edges of the game instead, narrower and over the
 * picture. The old touch layer is not shown on this profile (see [PhoneExternal]).
 *
 * While a menu is open each side narrows to what menus use (the `menu` layout of [SidePadLeft] and
 * [SidePadRight]), and the game's windows are placed between the two ([PhoneWindows]). Touches on
 * the game between the buttons go to the game, so its windows can be tapped directly.
 */
object GameScreenPad {

    /** Width of each side, in dp. */
    private const val SIDE_WIDTH_DP = 150

    /** Width of the left side while a menu is open, in dp. */
    private const val MENU_LEFT_WIDTH_DP = 104

    /** Width of the right side while a menu is open, in dp: wider, for the touchpad. */
    private const val MENU_RIGHT_WIDTH_DP = 144

    /** Room kept between a side and the game's windows while a menu is open, in dp. */
    private const val MENU_WINDOW_GAP_DP = 4

    /** How often the game is asked whether a menu is open. */
    private const val MENU_POLL_MS = 100L

    private val menuOpen = mutableStateOf(false)

    fun install(activity: Activity, container: FrameLayout) {
        // Each side is as wide as its content, so it narrows with the menu layout and the game
        // beside it takes the touches.
        container.addView(
            ComposeView(activity).apply {
                setContent {
                    // One side asks for both.
                    LaunchedEffect(Unit) {
                        while (true) {
                            menuOpen.value = runCatching { SDLActivity.companionGuiModeActive() }.getOrDefault(false)
                            delay(MENU_POLL_MS)
                        }
                    }
                    Side(menuOpen.value, MENU_LEFT_WIDTH_DP) { SidePadLeft(overGame = true, menu = it) }
                }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        container.addView(
            ComposeView(activity).apply {
                setContent { Side(menuOpen.value, MENU_RIGHT_WIDTH_DP) { SidePadRight(overGame = true, menu = it) } }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        )
    }

    /**
     * The part of the screen's width the left and the right side take while a menu is open, with
     * the gap, as fractions of the phone's longer side (the game runs in landscape).
     */
    fun menuSideFractions(context: Context): Pair<Float, Float> {
        val none = 0f to 0f
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return none
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        if (width <= 0) return none
        val px = metrics.density / width
        return (MENU_LEFT_WIDTH_DP + MENU_WINDOW_GAP_DP) * px to (MENU_RIGHT_WIDTH_DP + MENU_WINDOW_GAP_DP) * px
    }

    @Composable
    private fun Side(menu: Boolean, menuWidthDp: Int, content: @Composable (Boolean) -> Unit) {
        Box(Modifier.fillMaxHeight().width((if (menu) menuWidthDp else SIDE_WIDTH_DP).dp)) { content(menu) }
    }
}
