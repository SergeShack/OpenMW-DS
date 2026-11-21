package org.openmw.phone

import org.openmw.companion.GameStateRepository
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager
import org.openmw.utils.GyroControl
import kotlin.math.hypot

/**
 * The touch controller as the game sees it: drawn on the phone beside the framed companion
 * ([CompanionSidePad]) or over the game ([GameScreenPad]).
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

internal val PadBronze = Color(0xFFC8A062)
internal val PadFill = Color(0x402A2118)
internal val PadPressed = Color(0x80C8A062)

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
 * instead, see [MenuTouchpad]. Beside the companion it is that touchpad too while the menu is a
 * game window on the external screen, which cannot be touched ([PhoneExternal.menuOnGameScreen]).
 * While the menu is the companion's own screen it is the right stick, held from where the finger
 * landed, so the companion's lists scroll as they do on a controller.
 */
@Composable
internal fun LookPad(clickKey: Int, touchpadInMenus: Boolean, menu: Boolean, modifier: Modifier) {
    var touching by remember { mutableStateOf(false) }
    // A finger on the pad holds the gyroscope on (in that mode), so let go if the pad goes mid-touch.
    DisposableEffect(Unit) { onDispose { if (touching) GyroControl.touch(false) } }
    val scope = rememberCoroutineScope()
    val touchpad = remember { MenuTouchpad() }
    // Beside the companion: whether the menu is on the external screen, for the label.
    var mouseOnGameScreen by remember { mutableStateOf(false) }
    if (!touchpadInMenus) {
        LaunchedEffect(Unit) {
            while (true) {
                mouseOnGameScreen = PhoneExternal.menuOnGameScreen()
                delay(LABEL_POLL_MS)
            }
        }
    }
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
                    GyroControl.touch(true)
                    // Decided per touch: a menu does not open mid-drag.
                    val inMenu = runCatching { SDLActivity.companionGuiModeActive() }.getOrDefault(false)
                    if (inMenu && (touchpadInMenus || PhoneExternal.menuOnGameScreen())) {
                        with(touchpad) { track(down, scope) }
                        touching = false
                        GyroControl.touch(false)
                        return@awaitEachGesture
                    }
                    val asStick = inMenu
                    var last = down.position
                    var travelled = 0f
                    var lastTime = down.uptimeMillis
                    // While the D-pad or R3 has just been working the companion's inventory, a finger
                    // held still is R3 held: it uses the focused item. Otherwise holding is looking.
                    var holdToUse = !inMenu && GameStateRepository.inventoryPadRecent()
                    while (true) {
                        val event = if (holdToUse) {
                            val left = LOOK_HOLD_USE_MS - (lastTime - down.uptimeMillis)
                            withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            holdToUse = false
                            PadHaptics.play(PadHaptic.FACE)
                            GameStateRepository.useFocusedInventoryItem()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        lastTime = change.uptimeMillis
                        if (!change.pressed) break
                        val d = change.position - last
                        last = change.position
                        travelled += hypot(d.x, d.y)
                        if (travelled >= tapSlop) holdToUse = false
                        if (asStick) {
                            val o = change.position - down.position
                            VirtualPad.axis(VirtualPad.AXIS_RIGHT_X, o.x / stickRadius)
                            VirtualPad.axis(VirtualPad.AXIS_RIGHT_Y, o.y / stickRadius)
                        } else {
                            sendLook(d)
                        }
                    }
                    touching = false
                    GyroControl.touch(false)
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
            if ((menu && touchpadInMenus) || mouseOnGameScreen) "MOUSE" else "LOOK",
            color = PadBronze.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

/** How often the look pad beside the companion asks where the menu is, for its label. */
private const val LABEL_POLL_MS = 200L

/** Mouse units per pixel of finger movement on the look pad, at a [LookSpeed] of 100%. */
private const val LOOK_SCALE = 1.0f

private fun sendLook(d: Offset) {
    if (d.x == 0f && d.y == 0f) return
    val scale = LOOK_SCALE * LookSpeed.value
    runCatching {
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, d.x * scale, d.y * scale, true)
    }
}

/**
 * How fast the look pad, and aiming with the attack button, turn the camera, set in the editor.
 *
 * The game turns the camera by its Camera Sensitivity for the mouse, which the look pad is to it,
 * and for a controller's right stick alike. This speed is on top of that and only for the look
 * pad, so the sensitivity can be set for a real controller and the look pad kept as fast as wanted.
 */
internal object LookSpeed {
    private const val PREFS = "phone_pad"
    private const val KEY = "look_speed"

    const val DEFAULT = 1f
    const val MIN = 0.25f
    const val MAX = 4f
    const val STEP = 0.25f

    @Volatile
    var value = DEFAULT
        private set

    fun load(context: Context) {
        value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, DEFAULT).coerceIn(MIN, MAX)
    }

    fun save(context: Context, speed: Float) {
        value = speed.coerceIn(MIN, MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, value).apply()
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
 * It is the right trigger to the game, where the phone build binds Use, so the binding and any
 * rebinding stay the same. Sliding the finger while holding it aims as on the look pad, so you can
 * attack and turn in one touch. With the gyroscope worked by the attack button, the engine holds
 * it on while this is held and casts a ready spell as it is let go (gyro-on-demand.patch).
 */
@Composable
internal fun AttackButton(modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (pressed) VirtualPad.trigger(VirtualPad.AXIS_RIGHT_TRIGGER, false)
        }
    }
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
                    VirtualPad.trigger(VirtualPad.AXIS_RIGHT_TRIGGER, true)
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
                    VirtualPad.trigger(VirtualPad.AXIS_RIGHT_TRIGGER, false)
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
internal fun PadButton(
    label: String,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    labelColor: Color = PadBronze,
    haptic: PadHaptic = PadHaptic.FACE,
    labelSize: TextUnit = 16.sp,
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
        Text(label, color = labelColor, fontSize = labelSize, fontWeight = FontWeight.Bold, maxLines = 1)
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

/** How long a finger held still on the look pad counts as R3 held, see [LookPad]. */
private const val LOOK_HOLD_USE_MS = 400L
