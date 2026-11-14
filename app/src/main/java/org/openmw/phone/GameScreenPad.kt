package org.openmw.phone

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.libsdl.app.SDLActivity

/**
 * The touch controller over the game itself, for the Phone + external screen profile with no
 * external screen connected. The phone is then the only screen, so the controller the companion
 * has beside it is drawn over the game instead. The old touch layer is not shown on this profile
 * (see [PhoneExternal]).
 *
 * In the game each part is where the player put it in the editor ([PadEditor], [PadLayout]); at
 * first they make the same two columns as beside the companion. While a menu is open the two sides
 * narrow to what menus use (the `menu` layout of [SidePadLeft] and [SidePadRight]), and the game's
 * windows are placed between the two: the ones laid out from settings.cfg by [PhoneWindows], the
 * controller menus by the engine ([PhoneExternal.controllerMenuSides]).
 *
 * It is one view over the whole screen, but only the parts take touches: a touch anywhere else goes
 * to the game, so its windows can be tapped directly.
 *
 * Two small buttons, a part like the others, open the editor and hide the controller. Hidden, only
 * the button that shows it again is left. A real controller hides it too ([PadVisibility]).
 */
object GameScreenPad {

    /** Width of the left side while a menu is open, in dp. */
    private const val MENU_LEFT_WIDTH_DP = 104

    /** Width of the right side while a menu is open, in dp: wider, for the touchpad. */
    private const val MENU_RIGHT_WIDTH_DP = 144

    /** Room kept between a side and the game's windows while a menu is open, in dp. */
    private const val MENU_WINDOW_GAP_DP = 4

    /** How often the game is asked whether a menu is open. */
    private const val MENU_POLL_MS = 100L

    /** The edit and hide buttons are faint, as the keyboard button in the other corner is. */
    private const val TOOLS_ALPHA = 0.55f

    private val menuOpen = mutableStateOf(false)

    fun install(activity: Activity, container: FrameLayout) {
        container.addView(
            ComposeView(activity).apply { setContent { Pad() } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
    private fun Pad() {
        val context = LocalContext.current
        remember(context) { PadHaptics.init(context.applicationContext); true }
        LaunchedEffect(Unit) {
            while (true) {
                menuOpen.value = runCatching { SDLActivity.companionGuiModeActive() }.getOrDefault(false)
                delay(MENU_POLL_MS)
            }
        }

        val controller by rememberControllerConnected()
        var hiddenByHand by remember { mutableStateOf(PadVisibility.hiddenByHand(context)) }
        // Shown again over a real controller with the button; forgotten when the controller goes.
        var shownOverController by remember(controller) { mutableStateOf(false) }
        val hidden = if (controller) !shownOverController else hiddenByHand
        // The controller menus are centred between the sides while they are shown; they take the
        // new place the next time one opens.
        LaunchedEffect(hidden) {
            val (left, right) = if (hidden) 0f to 0f else menuSideFractions(context)
            PhoneExternal.controllerMenuSides(left, right)
        }
        var editing by remember { mutableStateOf(false) }
        var opacity by remember { mutableFloatStateOf(PadLayout.opacity(context)) }
        var saved by remember { mutableIntStateOf(0) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value
            val height = maxHeight.value
            val places = remember(width, height, saved) { PadLayout.load(context, width, height) }

            if (editing) {
                PadEditor(
                    start = places,
                    startOpacity = opacity,
                    width = width,
                    height = height,
                    defaults = { PadLayout.defaults(width, height) },
                ) { edited, editedOpacity ->
                    PadLayout.save(context, edited, width, height)
                    PadLayout.saveOpacity(context, editedOpacity)
                    opacity = editedOpacity
                    saved++
                    editing = false
                }
            } else {
                if (!hidden) {
                    Box(Modifier.fillMaxSize().alpha(opacity)) {
                        if (menuOpen.value) MenuSides() else Parts(places)
                    }
                }
                Tools(places.getValue(PadPart.TOOLS), hidden, onEdit = { editing = true }) {
                    if (controller) {
                        shownOverController = !shownOverController
                    } else {
                        hiddenByHand = !hiddenByHand
                        PadVisibility.setHiddenByHand(context, hiddenByHand)
                    }
                }
            }
        }
    }

    @Composable
    private fun Tools(tools: PartPlace, hidden: Boolean, onEdit: () -> Unit, onToggle: () -> Unit) {
        ToolButtons(
            modifier = Modifier.offset(tools.x.dp, tools.y.dp).size(tools.w.dp, tools.h.dp),
            hidden = hidden,
            onEdit = onEdit,
            onToggle = onToggle,
        )
    }

    /** Every part but the tool buttons, each where the layout puts it. */
    @Composable
    private fun Parts(places: Map<PadPart, PartPlace>) {
        Box(Modifier.fillMaxSize()) {
            for ((part, place) in places) {
                if (part == PadPart.TOOLS || place.hidden) continue
                key(part) {
                    PadPartContent(part, Modifier.offset(place.x.dp, place.y.dp).size(place.w.dp, place.h.dp))
                }
            }
        }
    }

    /** The two narrow sides used while a menu is open. They are not part of the editor's layout. */
    @Composable
    private fun MenuSides() {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopStart).fillMaxHeight().width(MENU_LEFT_WIDTH_DP.dp)) {
                SidePadLeft(overGame = true, menu = true)
            }
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(MENU_RIGHT_WIDTH_DP.dp)) {
                SidePadRight(overGame = true, menu = true)
            }
        }
    }

    /**
     * The edit and hide buttons, side by side at the right of their place. Hidden, only the button
     * that shows the controller again is left. Without [onEdit] and [onToggle] (in the editor)
     * they only show where they are.
     */
    @Composable
    internal fun ToolButtons(
        modifier: Modifier,
        hidden: Boolean,
        onEdit: (() -> Unit)?,
        onToggle: (() -> Unit)?,
    ) {
        BoxWithConstraints(modifier) {
            val size = minOf(maxHeight, maxWidth)
            Row(
                Modifier.fillMaxSize().alpha(TOOLS_ALPHA),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!hidden) ToolButton(Icons.Filled.Edit, "Edit touch controls", Modifier.size(size), onEdit)
                ToolButton(
                    if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    if (hidden) "Show touch controls" else "Hide touch controls",
                    Modifier.size(size),
                    onToggle,
                )
            }
        }
    }

    @Composable
    private fun ToolButton(icon: ImageVector, description: String, modifier: Modifier, onClick: (() -> Unit)?) {
        val click by rememberUpdatedState(onClick)
        Box(
            modifier
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .then(
                    if (onClick == null) Modifier else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            PadHaptics.play(PadHaptic.SYSTEM)
                            click?.invoke()
                        }
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxSize().padding(7.dp))
        }
    }
}

/** One part of the controller, filling [modifier]. */
@Composable
internal fun PadPartContent(part: PadPart, modifier: Modifier) {
    when (part) {
        PadPart.LT -> LtButton(modifier)
        PadPart.LB -> LbButton(modifier)
        PadPart.BACK -> BackButton(modifier)
        PadPart.DPAD -> DPad(modifier)
        PadPart.LEFT_STICK -> PadStick(
            axisX = VirtualPad.AXIS_LEFT_X,
            axisY = VirtualPad.AXIS_LEFT_Y,
            clickKey = KeyEvent.KEYCODE_BUTTON_THUMBL,
            modifier = modifier,
        )
        PadPart.START -> StartButton(modifier)
        PadPart.RB -> RbButton(modifier)
        PadPart.RT -> RtButton(modifier)
        PadPart.FACE -> FaceButtons(modifier)
        PadPart.ATTACK -> AttackButton(modifier)
        PadPart.LOOK -> LookPad(
            clickKey = KeyEvent.KEYCODE_BUTTON_THUMBR,
            touchpadInMenus = true,
            menu = false,
            modifier = modifier,
        )
        PadPart.TOOLS -> GameScreenPad.ToolButtons(modifier, hidden = false, onEdit = null, onToggle = null)
    }
}
