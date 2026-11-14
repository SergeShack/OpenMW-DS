package org.openmw.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Size of the corner a part is resized by, in dp. */
private const val HANDLE_DP = 26f

/** Opacity changes by this much per press. */
private const val OPACITY_STEP = 0.1f

/**
 * The touch controller's layout editor, over the game ([GameScreenPad]).
 *
 * Every part is drawn where it is now, with a frame over it: drag a part to move it, drag its
 * bottom right corner to resize it. A tapped part is selected, and the bar in the middle can hide
 * it or show it again; a hidden part stays in the editor, faint. The bar also sets how see-through
 * the whole controller is, puts every part back where it was at first, and closes the editor.
 *
 * The parts do not work while the editor is open: the frame over each takes its touches, and the
 * editor covers the game so nothing reaches it either.
 */
@Composable
internal fun PadEditor(
    start: Map<PadPart, PartPlace>,
    startOpacity: Float,
    width: Float,
    height: Float,
    defaults: () -> Map<PadPart, PartPlace>,
    onDone: (Map<PadPart, PartPlace>, Float) -> Unit,
) {
    val places = remember { mutableStateMapOf<PadPart, PartPlace>().apply { putAll(start) } }
    var selected by remember { mutableStateOf<PadPart?>(null) }
    var opacity by remember { mutableFloatStateOf(startOpacity) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // Taps between the parts clear the selection and go no further. A part's own touch is
            // consumed by the part first, so it does not clear the selection it just made.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    selected = null
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        for (part in PadPart.entries) {
            val place = places[part] ?: continue
            key(part) {
                EditedPart(
                    part = part,
                    place = place,
                    selected = selected == part,
                    opacity = opacity,
                    onSelect = { selected = part },
                    onMove = { dx, dy ->
                        val p = places[part] ?: return@EditedPart
                        places[part] = p.copy(x = p.x + dx, y = p.y + dy).inside(width, height)
                    },
                    onResize = { dw, dh ->
                        val p = places[part] ?: return@EditedPart
                        // Grows towards the bottom right, as far as the screen goes.
                        val w = (p.w + dw).coerceIn(PadLayout.MIN_SIZE_DP, width - p.x)
                        val h = (p.h + dh).coerceIn(PadLayout.MIN_SIZE_DP, height - p.y)
                        places[part] = p.copy(w = w, h = h)
                    },
                )
            }
        }

        EditorBar(
            modifier = Modifier.align(Alignment.Center),
            selected = selected,
            selectedHidden = selected?.let { places[it]?.hidden } ?: false,
            opacity = opacity,
            onOpacity = { opacity = it.coerceIn(PadLayout.OPACITY_MIN, 1f) },
            onToggleHidden = {
                val part = selected ?: return@EditorBar
                val p = places[part] ?: return@EditorBar
                places[part] = p.copy(hidden = !p.hidden)
            },
            onReset = {
                places.putAll(defaults())
                opacity = PadLayout.OPACITY_DEFAULT
                selected = null
            },
            onDone = { onDone(places.toMap(), opacity) },
        )
    }
}

@Composable
private fun EditedPart(
    part: PadPart,
    place: PartPlace,
    selected: Boolean,
    opacity: Float,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
) {
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val frame = if (selected) Color(0xFFFFE08A) else PadBronze.copy(alpha = 0.7f)
    Box(
        Modifier
            .offset(place.x.dp, place.y.dp)
            .size(place.w.dp, place.h.dp)
    ) {
        // The part itself, as it is drawn in the game. A hidden one is faint.
        Box(Modifier.fillMaxSize().alpha(if (place.hidden) 0.25f else opacity)) {
            PadPartContent(part, Modifier.fillMaxSize())
        }
        // The frame on top takes every touch, so the part under it does nothing.
        Box(
            Modifier
                .fillMaxSize()
                .border(if (selected) 2.dp else 1.dp, frame, RoundedCornerShape(6.dp))
                .pointerInput(part) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        select()
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            move(d.x / density, d.y / density)
                        }
                    }
                }
        ) {
            if (place.hidden) {
                Text(
                    "hidden",
                    color = frame,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Box(
            Modifier
                // Inside the part: a touch outside a part's bounds may not reach it.
                .align(Alignment.BottomEnd)
                .size(HANDLE_DP.dp)
                .background(frame, CircleShape)
                .pointerInput(part) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        select()
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            resize(d.x / density, d.y / density)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⤡", color = Color(0xFF2A2118), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EditorBar(
    modifier: Modifier,
    selected: PadPart?,
    selectedHidden: Boolean,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    onToggleHidden: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(Color(0xE0201810), RoundedCornerShape(12.dp))
            .border(1.dp, PadBronze, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Drag a part to move it, its corner to resize it.",
            color = PadBronze,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Opacity", color = PadBronze, fontSize = 14.sp)
            BarButton("−", enabled = opacity > PadLayout.OPACITY_MIN + 0.01f) { onOpacity(opacity - OPACITY_STEP) }
            Text(
                "${(opacity * 100).roundToInt()}%",
                color = PadBronze,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(44.dp),
            )
            BarButton("+", enabled = opacity < 0.99f) { onOpacity(opacity + OPACITY_STEP) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The tool buttons always stay, or the controller could not be shown again.
            val canHide = selected != null && selected != PadPart.TOOLS
            BarButton(if (canHide && selectedHidden) "Show part" else "Hide part", enabled = canHide, onClick = onToggleHidden)
            BarButton("Reset", onClick = onReset)
            BarButton("Done", onClick = onDone)
        }
    }
}

@Composable
private fun BarButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(PadFill, RoundedCornerShape(8.dp))
            .border(1.dp, PadBronze, RoundedCornerShape(8.dp))
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    var up = false
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) up = true
                    } while (!up)
                    if (enabled) {
                        PadHaptics.play(PadHaptic.FACE)
                        click()
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PadBronze, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
