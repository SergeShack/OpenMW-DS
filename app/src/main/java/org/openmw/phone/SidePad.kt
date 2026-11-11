package org.openmw.phone

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager
import kotlin.math.hypot

/**
 * A controller drawn on the phone, beside the framed companion ([FramedCompanion]).
 *
 * It is registered with SDL as an ordinary gamepad with two sticks, two triggers and the usual
 * buttons, so the game treats it exactly like a real one: the same bindings, the same menu
 * navigation, and it can be rebound in the game's controls menu.
 */
object VirtualPad {
    private const val TAG = "VirtualPad"

    // Android input device ids are small; this one cannot clash with a real device.
    private const val DEVICE_ID = 0x0D5_0D5

    // A, B, X, Y, Back, Start, both stick clicks, both shoulders and the d-pad (no Guide).
    private const val BUTTON_MASK = 0x7FDF

    // Left stick, right stick, left trigger, right trigger.
    private const val AXIS_COUNT = 6
    private const val AXIS_MASK = 0x3F

    const val AXIS_LEFT_X = 0
    const val AXIS_LEFT_Y = 1
    const val AXIS_RIGHT_X = 2
    const val AXIS_RIGHT_Y = 3
    const val AXIS_LEFT_TRIGGER = 4
    const val AXIS_RIGHT_TRIGGER = 5

    @Volatile
    private var added = false

    /** Registered on first use rather than up front, so the game's input is set up by then. */
    private fun ensureAdded() {
        if (added) return
        added = true
        val result = runCatching {
            SDLControllerManager.nativeAddJoystick(
                DEVICE_ID, "OpenMW-DS touch controller", "openmwds-touch-controller",
                0, 0, false, BUTTON_MASK, AXIS_COUNT, AXIS_MASK, 0, 0
            )
        }.onFailure { Log.e(TAG, "add failed", it) }.getOrNull()
        Log.i(TAG, "added: $result")
        // Triggers read -1 at rest, like a real pad's. An untouched axis is 0, half pulled.
        trigger(AXIS_LEFT_TRIGGER, false)
        trigger(AXIS_RIGHT_TRIGGER, false)
    }

    fun remove() {
        if (!added) return
        added = false
        runCatching { SDLControllerManager.nativeRemoveJoystick(DEVICE_ID) }
    }

    fun axis(axis: Int, value: Float) {
        ensureAdded()
        runCatching { SDLControllerManager.onNativeJoy(DEVICE_ID, axis, value.coerceIn(-1f, 1f)) }
    }

    fun trigger(axis: Int, pressed: Boolean) = axis(axis, if (pressed) 1f else -1f)

    fun button(keycode: Int, pressed: Boolean) {
        ensureAdded()
        runCatching {
            if (pressed) SDLControllerManager.onNativePadDown(DEVICE_ID, keycode)
            else SDLControllerManager.onNativePadUp(DEVICE_ID, keycode)
        }
    }
}

/** What a control feels like when touched. Each kind is distinct, so buttons can be told apart. */
enum class PadHaptic { ATTACK, FACE, TRIGGER, SHOULDER, DPAD, SYSTEM, RELEASE, STICK_DOWN, STICK_EDGE }

/**
 * Vibration for the touch controller. Uses the phone's predefined click effects where it has them,
 * and short pulses otherwise. Played on purpose rather than through the view's touch feedback,
 * which follows a system setting that is often off.
 */
object PadHaptics {
    @Volatile
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator != null) return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun play(kind: PadHaptic) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (kind) {
            PadHaptic.ATTACK -> pulse(32, 255)
            PadHaptic.FACE -> predefined(VibrationEffect.EFFECT_CLICK, 20, 180)
            PadHaptic.TRIGGER -> predefined(VibrationEffect.EFFECT_HEAVY_CLICK, 24, 200)
            PadHaptic.SHOULDER -> pulse(16, 140)
            PadHaptic.DPAD -> predefined(VibrationEffect.EFFECT_TICK, 10, 120)
            PadHaptic.SYSTEM -> predefined(VibrationEffect.EFFECT_DOUBLE_CLICK, 20, 180)
            PadHaptic.RELEASE -> pulse(8, 50)
            PadHaptic.STICK_DOWN -> predefined(VibrationEffect.EFFECT_TICK, 10, 100)
            PadHaptic.STICK_EDGE -> pulse(12, 90)
        }
        runCatching { v.vibrate(effect) }
    }

    private fun predefined(id: Int, fallbackMs: Long, fallbackAmplitude: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.createPredefined(id)
        else pulse(fallbackMs, fallbackAmplitude)

    private fun pulse(ms: Long, amplitude: Int): VibrationEffect = VibrationEffect.createOneShot(ms, amplitude)
}

private val PadBronze = Color(0xFFC8A062)
private val PadFill = Color(0x402A2118)
private val PadPressed = Color(0x80C8A062)

/** Left side: triggers and shoulder, Back, the d-pad and the movement stick. */
@Composable
fun SidePadLeft() {
    val context = LocalContext.current
    remember(context) { PadHaptics.init(context.applicationContext); true }
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PadRow {
            PadButton("LT", Modifier.weight(1f), haptic = PadHaptic.TRIGGER) { VirtualPad.trigger(VirtualPad.AXIS_LEFT_TRIGGER, it) }
            PadButton("LB", Modifier.weight(1f), haptic = PadHaptic.SHOULDER) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_L1, it) }
            PadButton("⧉", Modifier.weight(1f), haptic = PadHaptic.SYSTEM) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_SELECT, it) }
        }
        DPad(Modifier.weight(1f).fillMaxWidth())
        PadStick(
            axisX = VirtualPad.AXIS_LEFT_X,
            axisY = VirtualPad.AXIS_LEFT_Y,
            clickKey = KeyEvent.KEYCODE_BUTTON_THUMBL,
            modifier = Modifier.weight(1.3f).fillMaxWidth(),
        )
    }
}

/** Right side: Start, shoulder and trigger, the face buttons and the look pad. */
@Composable
fun SidePadRight() {
    val context = LocalContext.current
    remember(context) { PadHaptics.init(context.applicationContext); true }
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PadRow {
            PadButton("≡", Modifier.weight(1f), haptic = PadHaptic.SYSTEM) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_START, it) }
            PadButton("RB", Modifier.weight(1f), haptic = PadHaptic.SHOULDER) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_R1, it) }
            PadButton("RT", Modifier.weight(1f), haptic = PadHaptic.TRIGGER) { VirtualPad.trigger(VirtualPad.AXIS_RIGHT_TRIGGER, it) }
        }
        FaceButtons(Modifier.weight(1f).fillMaxWidth())
        AttackButton(Modifier.fillMaxWidth().height(60.dp))
        LookPad(
            clickKey = KeyEvent.KEYCODE_BUTTON_THUMBR,
            modifier = Modifier.weight(1.1f).fillMaxWidth(),
        )
    }
}

/**
 * Camera look as a touchpad: the view turns by as much as the finger moves and stops with it.
 * A stick turns at a speed instead, with the game's controller smoothing on top, which is hard
 * to aim with on glass. Sent as relative mouse motion, like the touch look on the game screen.
 * A short tap without moving clicks the right stick. While a menu is open it is the right stick
 * itself, held from where the finger landed, so lists scroll as they do on a controller.
 */
@Composable
private fun LookPad(clickKey: Int, modifier: Modifier) {
    var touching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .background(if (touching) PadPressed.copy(alpha = 0.2f) else PadFill, RoundedCornerShape(12.dp))
            .border(1.dp, PadBronze.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                val tapSlop = 8.dp.toPx()
                val stickRadius = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    touching = true
                    // In a menu the pad is the right stick instead, which scrolls lists, as on a
                    // real controller. Decided per touch: a menu does not open mid-drag.
                    val asStick = runCatching { SDLActivity.companionGuiModeActive() }.getOrDefault(false)
                    var last = down.position
                    var travelled = 0f
                    var lastTime = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        lastTime = change.uptimeMillis
                        if (!change.pressed) break
                        val d = change.position - last
                        last = change.position
                        travelled += hypot(d.x, d.y)
                        if (asStick) {
                            val o = change.position - down.position
                            VirtualPad.axis(VirtualPad.AXIS_RIGHT_X, o.x / stickRadius)
                            VirtualPad.axis(VirtualPad.AXIS_RIGHT_Y, o.y / stickRadius)
                        } else {
                            sendLook(d)
                        }
                    }
                    touching = false
                    if (asStick) {
                        VirtualPad.axis(VirtualPad.AXIS_RIGHT_X, 0f)
                        VirtualPad.axis(VirtualPad.AXIS_RIGHT_Y, 0f)
                    }
                    if (travelled < tapSlop && lastTime - down.uptimeMillis < 250) {
                        PadHaptics.play(PadHaptic.FACE)
                        scope.launch {
                            VirtualPad.button(clickKey, true)
                            delay(80)
                            VirtualPad.button(clickKey, false)
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            "LOOK",
            color = PadBronze.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

/** Mouse units per pixel of finger movement on the look pad. */
private const val LOOK_SCALE = 1.0f

private fun sendLook(d: Offset) {
    if (d.x == 0f && d.y == 0f) return
    runCatching {
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, d.x * LOOK_SCALE, d.y * LOOK_SCALE, true)
    }
}

/**
 * The main action (Use: attack or cast), a wide button just above the look pad, within reach of
 * the right thumb without covering any of the pad.
 * It is the right shoulder to the game, so the binding and any rebinding stay the same. Sliding
 * the finger while holding it aims as on the look pad, so you can attack and turn in one touch.
 */
@Composable
private fun AttackButton(modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (pressed) VirtualPad.button(KeyEvent.KEYCODE_BUTTON_R1, false) } }
    Box(
        modifier = modifier
            .background(if (pressed) PadPressed else Color(0x60402A18), RoundedCornerShape(30.dp))
            .border(2.dp, PadBronze, RoundedCornerShape(30.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    PadHaptics.play(PadHaptic.ATTACK)
                    VirtualPad.button(KeyEvent.KEYCODE_BUTTON_R1, true)
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        sendLook(change.position - last)
                        last = change.position
                    }
                    pressed = false
                    VirtualPad.button(KeyEvent.KEYCODE_BUTTON_R1, false)
                    PadHaptics.play(PadHaptic.RELEASE)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚔", color = PadBronze, fontSize = 30.sp)
    }
}

@Composable
private fun PadRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Four direction buttons in a cross. */
@Composable
private fun DPad(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val arm = Modifier.fillMaxSize(1f / 3f)
            PadButton("▲", arm.align(Alignment.TopCenter), haptic = PadHaptic.DPAD) { VirtualPad.button(KeyEvent.KEYCODE_DPAD_UP, it) }
            PadButton("▼", arm.align(Alignment.BottomCenter), haptic = PadHaptic.DPAD) { VirtualPad.button(KeyEvent.KEYCODE_DPAD_DOWN, it) }
            PadButton("◀", arm.align(Alignment.CenterStart), haptic = PadHaptic.DPAD) { VirtualPad.button(KeyEvent.KEYCODE_DPAD_LEFT, it) }
            PadButton("▶", arm.align(Alignment.CenterEnd), haptic = PadHaptic.DPAD) { VirtualPad.button(KeyEvent.KEYCODE_DPAD_RIGHT, it) }
        }
    }
}

/** A, B, X and Y in the usual diamond. */
@Composable
private fun FaceButtons(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val face = Modifier.fillMaxSize(0.36f)
            PadButton("Y", face.align(Alignment.TopCenter), CircleShape, Color(0xFFE0C040)) {
                VirtualPad.button(KeyEvent.KEYCODE_BUTTON_Y, it)
            }
            PadButton("A", face.align(Alignment.BottomCenter), CircleShape, Color(0xFF70C060)) {
                VirtualPad.button(KeyEvent.KEYCODE_BUTTON_A, it)
            }
            PadButton("X", face.align(Alignment.CenterStart), CircleShape, Color(0xFF5A90E0)) {
                VirtualPad.button(KeyEvent.KEYCODE_BUTTON_X, it)
            }
            PadButton("B", face.align(Alignment.CenterEnd), CircleShape, Color(0xFFE06050)) {
                VirtualPad.button(KeyEvent.KEYCODE_BUTTON_B, it)
            }
        }
    }
}

/**
 * A button that is held for as long as a finger is on it. Several can be held at once, and a
 * finger is let go of on cancel too, so nothing stays pressed.
 */
@Composable
private fun PadButton(
    label: String,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    labelColor: Color = PadBronze,
    haptic: PadHaptic = PadHaptic.FACE,
    onPress: (Boolean) -> Unit,
) {
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    // Let go if the button leaves the screen while held.
    DisposableEffect(Unit) { onDispose { if (pressed) press(false) } }
    Box(
        modifier = modifier
            .background(if (pressed) PadPressed else PadFill, shape)
            .border(1.5.dp, PadBronze, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    PadHaptics.play(haptic)
                    press(true)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    pressed = false
                    press(false)
                    // The d-pad is tapped quickly and often; a release pulse there is just noise.
                    if (haptic != PadHaptic.DPAD) PadHaptics.play(PadHaptic.RELEASE)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = labelColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A stick that starts where the finger lands anywhere in its area, so it does not have to be
 * found by feel. A short tap without moving clicks the stick.
 */
@Composable
private fun PadStick(axisX: Int, axisY: Int, clickKey: Int, modifier: Modifier) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            if (origin != null) {
                VirtualPad.axis(axisX, 0f)
                VirtualPad.axis(axisY, 0f)
            }
        }
    }
    Canvas(
        modifier = modifier
            .background(PadFill, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                val radius = 40.dp.toPx()
                val tapSlop = 8.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val start = down.position
                    val startTime = down.uptimeMillis
                    origin = start
                    knob = start
                    PadHaptics.play(PadHaptic.STICK_DOWN)
                    var atEdge = false
                    var moved = false
                    var lastTime = startTime
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        lastTime = change.uptimeMillis
                        if (!change.pressed) break
                        val d = change.position - start
                        val len = hypot(d.x, d.y)
                        if (len > tapSlop) moved = true
                        val clamped = if (len > radius) d * (radius / len) else d
                        // A pulse when the stick reaches full tilt, where walking turns to running.
                        val edge = len >= radius
                        if (edge && !atEdge) PadHaptics.play(PadHaptic.STICK_EDGE)
                        atEdge = edge
                        knob = start + clamped
                        VirtualPad.axis(axisX, clamped.x / radius)
                        VirtualPad.axis(axisY, clamped.y / radius)
                    }
                    origin = null
                    VirtualPad.axis(axisX, 0f)
                    VirtualPad.axis(axisY, 0f)
                    if (!moved && lastTime - startTime < 250) {
                        PadHaptics.play(PadHaptic.FACE)
                        scope.launch {
                            VirtualPad.button(clickKey, true)
                            delay(80)
                            VirtualPad.button(clickKey, false)
                        }
                    }
                }
            }
    ) {
        val ring = 40.dp.toPx()
        val stroke = Stroke(width = 1.5.dp.toPx())
        val o = origin
        if (o == null) {
            drawCircle(PadBronze.copy(alpha = 0.5f), radius = ring, center = center, style = stroke)
        } else {
            drawCircle(PadBronze, radius = ring, center = o, style = stroke)
            drawCircle(PadPressed, radius = ring * 0.45f, center = knob)
        }
    }
}
