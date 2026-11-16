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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Size of the corner a part is resized by, in dp. */
private const val HANDLE_DP = 26f

/** Opacity changes by this much per press. */
private const val OPACITY_STEP = 0.1f

/** What the editor has selected: a part of the controller, or a key button. */
private sealed interface EditTarget {
    data class Part(val part: PadPart) : EditTarget
    data class Key(val id: Long) : EditTarget
}

/** How many steps back Undo can go. */
private const val UNDO_STEPS = 50

/** The editor's state at one moment, for Undo. */
private class EditSnapshot(val parts: Map<PadPart, PartPlace>, val keys: List<KeyButton>, val opacity: Float)

/** What the editor hands back when it is closed. */
internal class PadEditResult(
    val parts: Map<PadPart, PartPlace>,
    val keys: List<KeyButton>,
    val opacity: Float,
)

/**
 * The touch controller's layout editor: over the game ([GameScreenPad]) with the controller's parts
 * and the key buttons, or beside the companion ([CompanionSideKeys]) with the key buttons only.
 *
 * Everything is drawn where it is now, with a frame over it: drag it to move it, drag its bottom
 * right corner to resize it. It stays inside [areas] (the whole screen, or the two sides beside the
 * companion) and can be dragged from one area to another. A tapped part or button is selected, and
 * the bar in the middle can hide a part or show it again, change a key button's key or delete it.
 * A hidden part stays in the editor, faint. The bar also adds key buttons, sets how see-through the
 * controller is ([startOpacity], when given), puts the controller's parts back where they were at
 * first ([defaults], when given; key buttons are left where they are), takes back the last changes
 * one by one (Undo), and closes the editor, keeping the changes (Done) or not (Cancel).
 *
 * Nothing works while the editor is open: the frame over each part takes its touches, and the
 * editor covers the rest of the screen so nothing reaches the game or the companion either.
 */
@Composable
internal fun PadEditor(
    areas: List<PadArea>,
    startParts: Map<PadPart, PartPlace>,
    startKeys: List<KeyButton>,
    startOpacity: Float?,
    defaults: (() -> Map<PadPart, PartPlace>)?,
    onCancel: () -> Unit,
    onDone: (PadEditResult) -> Unit,
) {
    val parts = remember { mutableStateMapOf<PadPart, PartPlace>().apply { putAll(startParts) } }
    val keys = remember { mutableStateListOf<KeyButton>().apply { addAll(startKeys) } }
    val density = LocalDensity.current.density
    var selected by remember { mutableStateOf<EditTarget?>(null) }
    var opacity by remember { mutableFloatStateOf(startOpacity ?: PadLayout.OPACITY_DEFAULT) }
    // The key picker is open: for a new button (null target) or for the selected one.
    var picking by remember { mutableStateOf(false) }
    var pickingFor by remember { mutableStateOf<Long?>(null) }
    // Where the bar is, in dp, so a new key button is not put under it.
    var barPlace by remember { mutableStateOf<PartPlace?>(null) }

    // Undo: the state before each change, newest last. A drag is one change, kept as it starts.
    val history = remember { mutableStateListOf<EditSnapshot>() }

    fun checkpoint() {
        history.add(EditSnapshot(parts.toMap(), keys.toList(), opacity))
        if (history.size > UNDO_STEPS) history.removeAt(0)
    }

    fun undo() {
        val last = history.removeLastOrNull() ?: return
        parts.clear()
        parts.putAll(last.parts)
        keys.clear()
        keys.addAll(last.keys)
        opacity = last.opacity
        selected = null
    }

    fun keyIndex(id: Long) = keys.indexOfFirst { it.id == id }

    fun move(place: PartPlace, dx: Float, dy: Float) = place.copy(x = place.x + dx, y = place.y + dy).within(areas)

    fun resize(place: PartPlace, dw: Float, dh: Float): PartPlace {
        // Grows towards the bottom right, as far as its area goes.
        val area = PadArea.nearest(areas, place.centerX, place.centerY)
        val w = (place.w + dw).coerceIn(PadLayout.MIN_SIZE_DP, maxOf(PadLayout.MIN_SIZE_DP, area.right - place.x))
        val h = (place.h + dh).coerceIn(PadLayout.MIN_SIZE_DP, maxOf(PadLayout.MIN_SIZE_DP, area.bottom - place.y))
        return place.copy(w = w, h = h)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // Taps between the parts clear the selection and go no further. A part's own touch is
            // consumed by the part first, so it does not clear the selection it just made. The
            // finger's moves are left alone: taken here, they would stop the key list scrolling.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    if (!picking) selected = null
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        for (part in PadPart.entries) {
            val place = parts[part] ?: continue
            key(part) {
                EditedItem(
                    place = place,
                    selected = selected == EditTarget.Part(part),
                    opacity = if (place.hidden) 0.25f else opacity,
                    label = if (place.hidden) "hidden" else null,
                    onSelect = { selected = EditTarget.Part(part) },
                    onChangeStart = ::checkpoint,
                    onMove = { dx, dy -> parts[part]?.let { parts[part] = move(it, dx, dy) } },
                    onResize = { dw, dh -> parts[part]?.let { parts[part] = resize(it, dw, dh) } },
                ) { PadPartContent(part, Modifier.fillMaxSize()) }
            }
        }
        for (button in keys) {
            key(button.id) {
                EditedItem(
                    place = button.place,
                    selected = selected == EditTarget.Key(button.id),
                    opacity = opacity,
                    label = null,
                    onSelect = { selected = EditTarget.Key(button.id) },
                    onChangeStart = ::checkpoint,
                    onMove = { dx, dy ->
                        val i = keyIndex(button.id)
                        if (i >= 0) keys[i] = keys[i].copy(place = move(keys[i].place, dx, dy))
                    },
                    onResize = { dw, dh ->
                        val i = keyIndex(button.id)
                        if (i >= 0) keys[i] = keys[i].copy(place = resize(keys[i].place, dw, dh))
                    },
                ) { KeyButtonView(button.keyCode, Modifier.fillMaxSize()) }
            }
        }

        if (picking) {
            // Covers the parts, so a touch beside the picker's keys does not reach one of them.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            )
            val current = pickingFor?.let { id -> keys.firstOrNull { it.id == id }?.keyCode }
            KeyPicker(
                modifier = Modifier.align(Alignment.Center),
                current = current,
                onPick = { code ->
                    checkpoint()
                    val id = pickingFor
                    if (id == null) {
                        // A new button goes in the middle of the area with the fewest buttons.
                        val area = areas.minBy { a -> keys.count { PadArea.nearest(areas, it.place.centerX, it.place.centerY) == a } }
                        val taken = parts.values.filter { !it.hidden } + keys.map { it.place } + listOfNotNull(barPlace)
                        val button = KeyButtonLayout.newButton(keys, code, area, taken)
                        keys.add(button)
                        selected = EditTarget.Key(button.id)
                    } else {
                        val i = keyIndex(id)
                        if (i >= 0) keys[i] = keys[i].copy(keyCode = code)
                    }
                    picking = false
                },
                onCancel = { picking = false },
            )
        } else {
            val target = selected
            EditorBar(
                modifier = Modifier.align(Alignment.Center).onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInParent()
                    barPlace = PartPlace(
                        bounds.left / density, bounds.top / density, bounds.width / density, bounds.height / density,
                    )
                },
                selected = target,
                selectedHidden = (target as? EditTarget.Part)?.let { parts[it.part]?.hidden } ?: false,
                opacity = if (startOpacity != null) opacity else null,
                onOpacity = {
                    checkpoint()
                    opacity = it.coerceIn(PadLayout.OPACITY_MIN, 1f)
                },
                onAddKey = {
                    pickingFor = null
                    picking = true
                },
                onChangeKey = {
                    pickingFor = (target as? EditTarget.Key)?.id ?: return@EditorBar
                    picking = true
                },
                onDeleteKey = {
                    val id = (target as? EditTarget.Key)?.id ?: return@EditorBar
                    checkpoint()
                    keys.removeAll { it.id == id }
                    selected = null
                },
                onToggleHidden = {
                    val part = (target as? EditTarget.Part)?.part ?: return@EditorBar
                    val p = parts[part] ?: return@EditorBar
                    checkpoint()
                    parts[part] = p.copy(hidden = !p.hidden)
                },
                onReset = defaults?.let { d ->
                    {
                        checkpoint()
                        parts.putAll(d())
                        opacity = PadLayout.OPACITY_DEFAULT
                        selected = null
                    }
                },
                canUndo = history.isNotEmpty(),
                onUndo = ::undo,
                onCancel = onCancel,
                onDone = { onDone(PadEditResult(parts.toMap(), keys.toList(), opacity)) },
            )
        }
    }
}

/** One part or key button in the editor, drawn as in the game with a frame over it. */
@Composable
private fun EditedItem(
    place: PartPlace,
    selected: Boolean,
    opacity: Float,
    label: String?,
    onSelect: () -> Unit,
    onChangeStart: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val select by rememberUpdatedState(onSelect)
    val changeStart by rememberUpdatedState(onChangeStart)
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val frame = if (selected) Color(0xFFFFE08A) else PadBronze.copy(alpha = 0.7f)
    Box(
        Modifier
            .offset(place.x.dp, place.y.dp)
            .size(place.w.dp, place.h.dp)
    ) {
        Box(Modifier.fillMaxSize().alpha(opacity)) { content() }
        // The frame on top takes every touch, so the part under it does nothing.
        Box(
            Modifier
                .fillMaxSize()
                .border(if (selected) 2.dp else 1.dp, frame, RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        select()
                        var started = false
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            if (!started) {
                                started = true
                                changeStart()
                            }
                            move(d.x / density, d.y / density)
                        }
                    }
                }
        ) {
            if (label != null) {
                Text(label, color = frame, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Box(
            Modifier
                // Inside the part: a touch outside a part's bounds may not reach it.
                .align(Alignment.BottomEnd)
                .size(HANDLE_DP.dp)
                .background(frame, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        select()
                        var started = false
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            if (!started) {
                                started = true
                                changeStart()
                            }
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

/**
 * The editor's bar. [opacity] is null where the controller has no opacity to set (beside the
 * companion), and [onReset] null where there is nothing to reset.
 */
@Composable
private fun EditorBar(
    modifier: Modifier,
    selected: EditTarget?,
    selectedHidden: Boolean,
    opacity: Float?,
    onOpacity: (Float) -> Unit,
    onAddKey: () -> Unit,
    onChangeKey: () -> Unit,
    onDeleteKey: () -> Unit,
    onToggleHidden: () -> Unit,
    onReset: (() -> Unit)?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
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
        if (opacity != null) {
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
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarButton("Add key", onClick = onAddKey)
            when (selected) {
                is EditTarget.Key -> {
                    BarButton("Change key", onClick = onChangeKey)
                    BarButton("Delete", onClick = onDeleteKey)
                }
                else -> {
                    // The tool buttons always stay, or the controller could not be shown again.
                    val part = (selected as? EditTarget.Part)?.part
                    val canHide = part != null && part != PadPart.TOOLS
                    BarButton(if (canHide && selectedHidden) "Show part" else "Hide part", enabled = canHide, onClick = onToggleHidden)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarButton("Undo", enabled = canUndo, onClick = onUndo)
            if (onReset != null) BarButton("Reset", onClick = onReset)
            BarButton("Cancel", onClick = onCancel)
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
