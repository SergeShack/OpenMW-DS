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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
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

/** Room left above the controller when it is over the game, for the corner buttons. */
internal val OVER_GAME_TOP = 52.dp

/** Height of the face buttons in the menu layout: as wide as the left side's d-pad there. */
private val MENU_FACE_BUTTONS = 88.dp

internal val PadBronze = Color(0xFFC8A062)
internal val PadFill = Color(0x402A2118)
internal val PadPressed = Color(0x80C8A062)

/**
 * Left side: triggers and shoulder, Back, the d-pad and the movement stick.
 *
 * [overGame] is the phone-alone layout, drawn over the game: it leaves the top corners free for
 * the keyboard and quick slot buttons.
 *
 * [menu] is the narrow layout used over the game while a menu is open (see [GameScreenPad]): only
 * what menus use, so the game's windows fit between the two sides. The stick goes, since the
 * cursor is moved on the right side's touchpad, and Start moves over here beside Back.
 */
@Composable
fun SidePadLeft(overGame: Boolean = false, menu: Boolean = false) {
    val context = LocalContext.current
    remember(context) { PadHaptics.init(context.applicationContext); true }
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp).padding(top = if (overGame) OVER_GAME_TOP else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (menu) {
            PadRow {
                LtButton(Modifier.weight(1f))
                LbButton(Modifier.weight(1f))
            }
            PadRow {
                BackButton(Modifier.weight(1f))
                StartButton(Modifier.weight(1f))
            }
            DPad(Modifier.fillMaxWidth().aspectRatio(1f))
        } else {
            PadRow {
                LtButton(Modifier.weight(1f))
                LbButton(Modifier.weight(1f))
                BackButton(Modifier.weight(1f))
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
}

/**
 * Right side: Start, shoulder and trigger, the face buttons and the look pad. See [SidePadLeft].
 *
 * In the [menu] layout the attack button goes and the look pad is a touchpad for the game's cursor.
 * This side is wider than the left one there, and the face buttons keep the size they have on the
 * left side's width, so the extra width goes to the touchpad.
 */
@Composable
fun SidePadRight(overGame: Boolean = false, menu: Boolean = false) {
    val context = LocalContext.current
    remember(context) { PadHaptics.init(context.applicationContext); true }
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp).padding(top = if (overGame) OVER_GAME_TOP else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (menu) {
            PadRow {
                RbButton(Modifier.weight(1f))
                RtButton(Modifier.weight(1f))
            }
            FaceButtons(Modifier.fillMaxWidth().height(MENU_FACE_BUTTONS))
            LookPad(
                clickKey = KeyEvent.KEYCODE_BUTTON_THUMBR,
                touchpadInMenus = overGame,
                menu = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            PadRow {
                StartButton(Modifier.weight(1f))
                RbButton(Modifier.weight(1f))
                RtButton(Modifier.weight(1f))
            }
            FaceButtons(Modifier.weight(1f).fillMaxWidth())
            AttackButton(Modifier.fillMaxWidth().height(60.dp))
            LookPad(
                clickKey = KeyEvent.KEYCODE_BUTTON_THUMBR,
                touchpadInMenus = overGame,
                menu = false,
                modifier = Modifier.weight(1.1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun LtButton(modifier: Modifier) =
    PadButton("LT", modifier, haptic = PadHaptic.TRIGGER) { VirtualPad.trigger(VirtualPad.AXIS_LEFT_TRIGGER, it) }

@Composable
internal fun LbButton(modifier: Modifier) =
    PadButton("LB", modifier, haptic = PadHaptic.SHOULDER) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_L1, it) }

@Composable
internal fun BackButton(modifier: Modifier) =
    PadButton("⧉", modifier, haptic = PadHaptic.SYSTEM) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_SELECT, it) }

@Composable
internal fun StartButton(modifier: Modifier) =
    PadButton("≡", modifier, haptic = PadHaptic.SYSTEM) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_START, it) }

@Composable
internal fun RbButton(modifier: Modifier) =
    PadButton("RB", modifier, haptic = PadHaptic.SHOULDER) { VirtualPad.button(KeyEvent.KEYCODE_BUTTON_R1, it) }

@Composable
internal fun RtButton(modifier: Modifier) =
    PadButton("RT", modifier, haptic = PadHaptic.TRIGGER) { VirtualPad.trigger(VirtualPad.AXIS_RIGHT_TRIGGER, it) }

/**
 * Camera look as a touchpad: the view turns by as much as the finger moves and stops with it.
 * A stick turns at a speed instead, with the game's controller smoothing on top, which is hard
 * to aim with on glass. Sent as relative mouse motion, like the touch look on the game screen.
 * A short tap without moving clicks the right stick.
 *
 * While a menu is open over the game ([touchpadInMenus]) it is a touchpad for the game's cursor
 * instead, see [MenuTouchpad]. Beside the companion, where the menus are mostly the companion's own,
 * it is the right stick in a menu, held from where the finger landed, so lists scroll as they do on
 * a controller.
 */
@Composable
internal fun LookPad(clickKey: Int, touchpadInMenus: Boolean, menu: Boolean, modifier: Modifier) {
    var touching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val touchpad = remember { MenuTouchpad() }
    Box(
        modifier = modifier
            .background(if (touching) PadPressed.copy(alpha = 0.2f) else PadFill, RoundedCornerShape(12.dp))
            .border(1.dp, PadBronze.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .pointerInput(touchpadInMenus) {
                val tapSlop = 8.dp.toPx()
                val stickRadius = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    touching = true
                    // Decided per touch: a menu does not open mid-drag.
                    val inMenu = runCatching { SDLActivity.companionGuiModeActive() }.getOrDefault(false)
                    if (inMenu && touchpadInMenus) {
                        with(touchpad) { track(down, scope) }
                        touching = false
                        return@awaitEachGesture
                    }
                    val asStick = inMenu
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
            if (menu && touchpadInMenus) "MOUSE" else "LOOK",
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

/** Cursor pixels per pixel of finger movement on the menu touchpad: slow, and fast. */
private const val TOUCHPAD_GAIN_SLOW = 1.5f
private const val TOUCHPAD_GAIN_FAST = 5f

/** Finger speeds, in dp per ms, where the gain starts to rise from slow and where it reaches fast. */
private const val TOUCHPAD_SPEED_SLOW = 0.1f
private const val TOUCHPAD_SPEED_FAST = 1f

/** Finger travel per notch of the mouse wheel when scrolling with two fingers. */
private val TOUCHPAD_SCROLL_STEP = 16.dp

/** A touch that starts this soon after a tap holds the mouse button down, for dragging. */
private const val TOUCHPAD_DRAG_AFTER_TAP_MS = 250L

/**
 * The look pad as a touchpad, while a menu is open over the game. Like a laptop's:
 * - one finger moves the cursor by as much as it moves, faster for a quick swipe, and the cursor
 *   stays where it is between touches, so it crosses the screen in a few strokes;
 * - a tap clicks where the cursor is;
 * - tap, then touch again and move: the button is held while moving, for sliders and scroll bars
 *   (without moving it is a double click);
 * - two fingers up or down turn the mouse wheel.
 *
 * Sent as relative mouse motion, which SDL adds to the cursor's position, and mouse buttons, so the
 * game sees an ordinary mouse.
 */
private class MenuTouchpad {
    private var lastTapUp = 0L
    private var restX = 0f
    private var restY = 0f

    suspend fun AwaitPointerEventScope.track(down: PointerInputChange, scope: CoroutineScope) {
        val tapSlop = 8.dp.toPx()
        val scrollStep = TOUCHPAD_SCROLL_STEP.toPx()
        val dpPx = 1.dp.toPx()
        val drag = down.uptimeMillis - lastTapUp < TOUCHPAD_DRAG_AFTER_TAP_MS
        lastTapUp = 0L
        if (drag) mouseButton(true)
        var last = down.position
        var lastTime = down.uptimeMillis
        var endTime = down.uptimeMillis
        var travelled = 0f
        var scrolling = false
        var scrollY = 0f
        var scrollRest = 0f
        // Let go of a held button even if the pad leaves the screen mid-touch (the menu closed).
        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                event.changes.firstOrNull { it.id == down.id }?.let { endTime = it.uptimeMillis }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2 && !drag) {
                    // Fingers moving down show what is above, as on a phone: the wheel turns up.
                    val y = pressed.map { it.position.y }.average().toFloat()
                    if (!scrolling) {
                        scrolling = true
                        scrollY = y
                        continue
                    }
                    scrollRest += y - scrollY
                    scrollY = y
                    while (scrollRest >= scrollStep) { wheel(1f); scrollRest -= scrollStep }
                    while (scrollRest <= -scrollStep) { wheel(-1f); scrollRest += scrollStep }
                    continue
                }
                // Once two fingers have scrolled, the rest of the touch does not move the cursor.
                if (scrolling) continue
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) break
                val d = change.position - last
                val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                last = change.position
                lastTime = change.uptimeMillis
                val length = hypot(d.x, d.y)
                travelled += length
                val speed = length / dpPx / dt
                val t = ((speed - TOUCHPAD_SPEED_SLOW) / (TOUCHPAD_SPEED_FAST - TOUCHPAD_SPEED_SLOW)).coerceIn(0f, 1f)
                moveCursor(d * (TOUCHPAD_GAIN_SLOW + (TOUCHPAD_GAIN_FAST - TOUCHPAD_GAIN_SLOW) * t))
            }
        } finally {
            if (drag) mouseButton(false)
        }
        if (!drag && !scrolling && travelled < tapSlop && endTime - down.uptimeMillis < 250) {
            lastTapUp = endTime
            PadHaptics.play(PadHaptic.FACE)
            scope.launch {
                mouseButton(true)
                delay(40)
                mouseButton(false)
            }
        }
    }

    /** SDL takes whole pixels, so what is left over is kept for the next move. */
    private fun moveCursor(d: Offset) {
        restX += d.x
        restY += d.y
        val x = restX.toInt()
        val y = restY.toInt()
        if (x == 0 && y == 0) return
        restX -= x
        restY -= y
        runCatching { SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), true) }
    }

    private fun mouseButton(pressed: Boolean) {
        runCatching {
            if (pressed) SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, 0f, 0f, true)
            else SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true)
        }
    }

    private fun wheel(notches: Float) {
        runCatching { SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0f, notches, false) }
    }
}

/**
 * The main action (Use: attack or cast), a wide button just above the look pad, within reach of
 * the right thumb without covering any of the pad.
 * It is the right shoulder to the game, so the binding and any rebinding stay the same. Sliding
 * the finger while holding it aims as on the look pad, so you can attack and turn in one touch.
 */
@Composable
internal fun AttackButton(modifier: Modifier) {
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
internal fun DPad(modifier: Modifier) {
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
internal fun FaceButtons(modifier: Modifier) {
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
internal fun PadStick(axisX: Int, axisY: Int, clickKey: Int, modifier: Modifier) {
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
