package org.openmw.phone

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * The two sides beside the framed companion ([FramedCompanion]).
 *
 * Without a real controller they hold the touch controller, its parts where the player put them,
 * at first two columns ([PadLayout]), and the key buttons made for it. Two small buttons at the top
 * of the right side open the editor and hide the controller, as over the game ([GameScreenPad]).
 *
 * While a real controller is connected the touch controller goes, and the sides hold the key buttons
 * made for that case instead: a controller has no keys for the mods they are made for. Only the edit
 * button is left then. The two cases have layouts of their own ([PadSurface]).
 *
 * One view over the whole companion screen, on top of it. Only the parts, the key buttons and the
 * tool buttons take touches; a touch anywhere else goes to the companion under it. The editor
 * ([PadEditor]) keeps everything within the sides.
 */
internal object CompanionSidePad {

    /** Size of the edit button with a real controller and its distance from the edges, in dp. */
    private const val TOOL_DP = 34f
    private const val TOOL_PAD_DP = 8f

    /** The edit button is faint, as the tool buttons are. */
    private const val TOOL_ALPHA = 0.55f

    /**
     * The view, for [FramedCompanion] to add over everything else. [sideWidthPx] is the width of
     * each side, 0 while the companion fills the screen.
     */
    fun view(activity: ComponentActivity, visible: StateFlow<Boolean>, sideWidthPx: State<Int>): ComposeView =
        ComposeView(activity).apply {
            setContent {
                val shown by visible.collectAsState()
                if (shown && sideWidthPx.value > 0) Sides(sideWidthPx.value)
            }
        }

    @Composable
    private fun Sides(sideWidthPx: Int) {
        val context = LocalContext.current
        remember(context) {
            PadHaptics.init(context.applicationContext)
            LookSpeed.load(context)
            true
        }
        val side = sideWidthPx / LocalDensity.current.density
        val controller by rememberControllerConnected()
        val surface = if (controller) PadSurface.BESIDE_COMPANION else PadSurface.BESIDE_COMPANION_TOUCH
        var hiddenByHand by remember { mutableStateOf(PadVisibility.hiddenByHand(context, PadSurface.BESIDE_COMPANION_TOUCH)) }
        // A controller connected or gone while editing closes the editor without keeping anything.
        var editing by remember(surface) { mutableStateOf(false) }
        var saved by remember { mutableIntStateOf(0) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value
            val height = maxHeight.value
            val areas = remember(width, height, side) {
                listOf(PadArea(0f, 0f, side, height), PadArea(width - side, 0f, side, height))
            }
            val parts = remember(areas, surface, saved) {
                if (surface.hasParts) PadLayout.load(context, surface, areas) else emptyMap()
            }
            val keys = remember(areas, surface, saved) { KeyButtonLayout.load(context, surface, areas) }

            if (editing) {
                PadEditor(
                    areas = areas,
                    startParts = parts,
                    startKeys = keys,
                    startOpacity = null,
                    startLookSpeed = if (surface.hasParts) LookSpeed.value else null,
                    defaults = if (surface.hasParts) ({ PadLayout.defaults(surface, areas) }) else null,
                    onCancel = { editing = false },
                ) { edited ->
                    if (surface.hasParts) {
                        PadLayout.save(context, surface, edited.parts, areas)
                        LookSpeed.save(context, edited.lookSpeed)
                    }
                    KeyButtonLayout.save(context, surface, edited.keys, areas)
                    saved++
                    editing = false
                }
            } else if (surface.hasParts) {
                if (!hiddenByHand) {
                    PlacedParts(parts, besideCompanion = true)
                    PlacedKeys(keys)
                }
                val tools = parts.getValue(PadPart.TOOLS)
                GameScreenPad.ToolButtons(
                    modifier = Modifier.offset(tools.x.dp, tools.y.dp).size(tools.w.dp, tools.h.dp),
                    hidden = hiddenByHand,
                    onEdit = { editing = true },
                ) {
                    hiddenByHand = !hiddenByHand
                    PadVisibility.setHiddenByHand(context, hiddenByHand, PadSurface.BESIDE_COMPANION_TOUCH)
                }
            } else {
                PlacedKeys(keys)
                // Top right corner of the right side, where the tool buttons are without a controller.
                Box(
                    Modifier
                        .offset((width - TOOL_PAD_DP - TOOL_DP).dp, TOOL_PAD_DP.dp)
                        .size(TOOL_DP.dp)
                        .alpha(TOOL_ALPHA)
                ) {
                    GameScreenPad.ToolButton(Icons.Filled.Edit, "Edit key buttons", Modifier.fillMaxSize()) {
                        editing = true
                    }
                }
            }
        }
    }
}
