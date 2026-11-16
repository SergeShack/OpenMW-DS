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
 * first they make two columns at the sides. While a menu is open the controller has a layout of its
 * own ([PadSurface.OVER_GAME_MENU]): at first two narrow sides, and the
 * editor opened then arranges that one. The game's windows are placed between its sides: the ones
 * laid out from settings.cfg by [PhoneWindows], the controller menus by the engine
 * ([PhoneExternal.controllerMenuSides]).
 *
 * It is one view over the whole screen, but only the parts take touches: a touch anywhere else goes
 * to the game, so its windows can be tapped directly.
 *
 * Two small buttons, a part like the others, open the editor and hide the controller. Hidden, only
 * the button that shows it again is left. A real controller hides it too ([PadVisibility]).
 *
 * The player's key buttons ([KeyButton]) are drawn over the game as well. A real controller does
 * not hide them, since it has no keys for the mods they are made for; the hide button does.
 */
object GameScreenPad {

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
     * The part of the screen's width left of the game's windows and right of them while a menu is
     * open, as fractions of the phone's longer side (the game runs in landscape): as far as the menu
     * layout reaches in from each side ([PadLayout.menuSides]).
     */
    fun menuSideFractions(context: Context): Pair<Float, Float> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 0f to 0f
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val density = metrics.density.takeIf { it > 0f } ?: return 0f to 0f
        val width = maxOf(metrics.widthPixels, metrics.heightPixels) / density
        val height = minOf(metrics.widthPixels, metrics.heightPixels) / density
        return menuSideFractions(context, PadArea(0f, 0f, width, height))
    }

    private fun menuSideFractions(context: Context, screen: PadArea): Pair<Float, Float> {
        val areas = listOf(screen)
        return PadLayout.menuSides(
            PadLayout.load(context, PadSurface.OVER_GAME_MENU, areas),
            KeyButtonLayout.load(context, PadSurface.OVER_GAME_MENU, areas),
            screen,
        )
    }

    @Composable
    private fun Pad() {
        val context = LocalContext.current
        remember(context) {
            PadHaptics.init(context.applicationContext)
            LookSpeed.load(context)
            true
        }
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
        // The key buttons stay over a real controller; only the hide button hides them.
        val keysHidden = !controller && hiddenByHand
        // The layout being edited: the one for menus when the editor was opened in a menu.
        var editing by remember { mutableStateOf<PadSurface?>(null) }
        var opacity by remember { mutableFloatStateOf(PadLayout.opacity(context)) }
        var saved by remember { mutableIntStateOf(0) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value
            val height = maxHeight.value
            val areas = remember(width, height) { listOf(PadArea(0f, 0f, width, height)) }
            val surface = if (menuOpen.value) PadSurface.OVER_GAME_MENU else PadSurface.OVER_GAME
            val places = remember(areas, saved, surface) { PadLayout.load(context, surface, areas) }
            val keys = remember(areas, saved, surface) { KeyButtonLayout.load(context, surface, areas) }
            // The controller menus are centred between the menu layout's sides while it is shown;
            // they take the new place the next time one opens.
            LaunchedEffect(hidden, saved, areas) {
                val (left, right) = if (hidden) 0f to 0f else menuSideFractions(context, areas.first())
                PhoneExternal.controllerMenuSides(left, right)
            }

            val edited = editing
            if (edited != null) {
                val startParts = remember(edited, saved) { PadLayout.load(context, edited, areas) }
                val startKeys = remember(edited, saved) { KeyButtonLayout.load(context, edited, areas) }
                PadEditor(
                    areas = areas,
                    startParts = startParts,
                    startKeys = startKeys,
                    startOpacity = opacity,
                    startLookSpeed = LookSpeed.value,
                    defaults = { PadLayout.defaults(edited, areas) },
                    onSave = { result ->
                        PadLayout.save(context, edited, result.parts, areas)
                        PadLayout.saveOpacity(context, result.opacity)
                        LookSpeed.save(context, result.lookSpeed)
                        KeyButtonLayout.save(context, edited, result.keys, areas)
                    },
                ) { reload ->
                    // Read back what was kept, or the saved layout that was loaded.
                    if (reload) LookSpeed.load(context)
                    opacity = PadLayout.opacity(context)
                    saved++
                    editing = null
                    // The game's windows follow the menu layout's sides at once.
                    if (!hidden) {
                        val (left, right) = menuSideFractions(context, areas.first())
                        PhoneExternal.refitWindows(context, left, right)
                    }
                }
            } else {
                if (!hidden) {
                    Box(Modifier.fillMaxSize().alpha(opacity)) {
                        PlacedParts(places, besideCompanion = false, menu = surface == PadSurface.OVER_GAME_MENU)
                    }
                }
                if (!keysHidden) {
                    Box(Modifier.fillMaxSize().alpha(opacity)) { PlacedKeys(keys) }
                }
                Tools(places.getValue(PadPart.TOOLS), hidden, canEdit = !keysHidden, onEdit = { editing = surface }) {
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
    private fun Tools(tools: PartPlace, hidden: Boolean, canEdit: Boolean, onEdit: () -> Unit, onToggle: () -> Unit) {
        ToolButtons(
            modifier = Modifier.offset(tools.x.dp, tools.y.dp).size(tools.w.dp, tools.h.dp),
            hidden = hidden,
            canEdit = canEdit,
            onEdit = onEdit,
            onToggle = onToggle,
        )
    }

    /**
     * The edit and hide buttons, side by side at the right of their place. Hidden, only the button
     * that shows the controller again is left, and the edit button while there are key buttons to
     * edit ([canEdit]: hidden by a real controller rather than by hand). Without [onEdit] and
     * [onToggle] (in the editor) they only show where they are.
     */
    @Composable
    internal fun ToolButtons(
        modifier: Modifier,
        hidden: Boolean,
        canEdit: Boolean = !hidden,
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
                if (canEdit) ToolButton(Icons.Filled.Edit, "Edit touch controls", Modifier.size(size), onEdit)
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
    internal fun ToolButton(icon: ImageVector, description: String, modifier: Modifier, onClick: (() -> Unit)?) {
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

/** Every part but the tool buttons, each where the layout puts it. [menu]: the menu layout over the game. */
@Composable
internal fun PlacedParts(places: Map<PadPart, PartPlace>, besideCompanion: Boolean, menu: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        for ((part, place) in places) {
            if (part == PadPart.TOOLS || place.hidden) continue
            key(part) {
                PadPartContent(part, Modifier.offset(place.x.dp, place.y.dp).size(place.w.dp, place.h.dp), besideCompanion, menu)
            }
        }
    }
}

/**
 * One part of the controller, filling [modifier]. [besideCompanion]: the look pad is the right stick
 * in the companion's own menus there, see [LookPad]. [menu]: the menu layout over the game, where the
 * look pad is the touchpad for the game's cursor and says so.
 */
@Composable
internal fun PadPartContent(part: PadPart, modifier: Modifier, besideCompanion: Boolean = false, menu: Boolean = false) {
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
            touchpadInMenus = !besideCompanion,
            menu = menu,
            modifier = modifier,
        )
        PadPart.TOOLS -> GameScreenPad.ToolButtons(modifier, hidden = false, onEdit = null, onToggle = null)
    }
}
