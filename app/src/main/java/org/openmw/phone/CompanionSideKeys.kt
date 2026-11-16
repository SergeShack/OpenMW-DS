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
import androidx.compose.runtime.key
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
 * The key buttons beside the framed companion ([FramedCompanion]), while a real controller is
 * connected. The touch controller then leaves the two sides empty, and the player's key buttons
 * use them: a controller has no keys for the mods they are made for.
 *
 * One view over the whole companion screen, on top of it. Only the key buttons and the edit button
 * take touches; a touch anywhere else goes to the companion under it. The editor ([PadEditor]) is
 * opened with the edit button at the top of the right side and keeps the buttons within the sides.
 */
internal object CompanionSideKeys {

    /** Size of the edit button and its distance from the edges, in dp. */
    private const val TOOL_DP = 34f
    private const val TOOL_PAD_DP = 8f

    /** The edit button is faint, as over the game. */
    private const val TOOL_ALPHA = 0.55f

    /**
     * The view, for [FramedCompanion] to add over everything else. [sideWidthPx] is the width of
     * each side, 0 while the companion fills the screen.
     */
    fun view(activity: ComponentActivity, visible: StateFlow<Boolean>, sideWidthPx: State<Int>): ComposeView =
        ComposeView(activity).apply {
            setContent {
                val shown by visible.collectAsState()
                val controller by rememberControllerConnected()
                if (shown && controller && sideWidthPx.value > 0) Keys(sideWidthPx.value)
            }
        }

    @Composable
    private fun Keys(sideWidthPx: Int) {
        val context = LocalContext.current
        remember(context) { PadHaptics.init(context.applicationContext); true }
        val side = sideWidthPx / LocalDensity.current.density
        var editing by remember { mutableStateOf(false) }
        var saved by remember { mutableIntStateOf(0) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value
            val height = maxHeight.value
            val areas = remember(width, height, side) {
                listOf(PadArea(0f, 0f, side, height), PadArea(width - side, 0f, side, height))
            }
            val keys = remember(areas, saved) { KeyButtonLayout.load(context, KeySurface.BESIDE_COMPANION, areas) }

            if (editing) {
                PadEditor(
                    areas = areas,
                    startParts = emptyMap(),
                    startKeys = keys,
                    startOpacity = null,
                    defaults = null,
                    onCancel = { editing = false },
                ) { edited ->
                    KeyButtonLayout.save(context, KeySurface.BESIDE_COMPANION, edited.keys, areas)
                    saved++
                    editing = false
                }
            } else {
                for (button in keys) {
                    key(button.id) {
                        KeyButtonView(
                            button.keyCode,
                            Modifier.offset(button.place.x.dp, button.place.y.dp).size(button.place.w.dp, button.place.h.dp),
                        )
                    }
                }
                // Top right corner of the right side, where the hide and edit buttons are over the game.
                Box(
                    Modifier
                        .offset((width - TOOL_PAD_DP - TOOL_DP).dp, TOOL_PAD_DP.dp)
                        .size(TOOL_DP.dp)
                ) {
                    Box(Modifier.fillMaxSize().alpha(TOOL_ALPHA)) {
                        GameScreenPad.ToolButton(Icons.Filled.Edit, "Edit key buttons", Modifier.fillMaxSize()) {
                            editing = true
                        }
                    }
                }
            }
        }
    }
}
