package org.openmw.utils

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withContext
import org.openmw.Constants
import org.openmw.EngineActivity
import org.openmw.ui.view.getSettingInSection
import org.openmw.ui.view.setSettingInSection
import java.io.File

/**
 * The gyroscope on demand.
 *
 * The engine turns the camera with the device's gyroscope when `[Input] enable gyroscope` is on,
 * all the time outside menus. Here the player picks, in the launcher, when it does: always, while a
 * controller button is held, or switched on and off by that button. Off is the engine's setting
 * itself, so the launcher and the settings editor always agree.
 *
 * The choice is pushed to the engine when the game starts ([start]), and the button's presses as
 * they happen ([onPadKey]). The attack button ([ATTACK], the default, While held only) is read by
 * the engine itself from the game's Use action, whatever it is bound to; a spell is then cast as
 * Use is let go, so there is time to aim, and the gyroscope stays on until the cast is over. The engine decides from them on its own thread (gyro-on-demand.patch),
 * where it knows whether a menu is open: a press in a menu does not switch the gyroscope. The button
 * still reaches the game too, so in menus it does what it always did.
 */
@OptIn(InternalCoroutinesApi::class)
object GyroControl {
    private const val TAG = "GyroControl"

    /** [HOLD]: while the button is held, or a finger is on the phone build's look pad, see [touch]. */
    enum class Mode(val engineValue: Int) {
        OFF(0),
        ALWAYS(0),
        HOLD(1),
        TOGGLE(2),
    }

    /**
     * Which gyroscope turns the camera: a controller's own when it has one the game can read
     * ([PadGyro], or SDL for one on USB), or always the phone's, for a controller the phone is held in.
     */
    enum class Source { AUTO, PHONE }

    /** The sources the launcher offers, in its order. */
    val SOURCES = listOf(Source.AUTO, Source.PHONE)

    /**
     * The axes of a controller's gyroscope read over Bluetooth ([PadGyro]): as Android gives them, or
     * with the first two swapped, as a DualSense needs. Auto swaps them for Sony's controllers.
     */
    enum class Axes { AUTO, STANDARD, SWAPPED }

    /** The axes choices the launcher offers, in its order. */
    val AXES = listOf(Axes.AUTO, Axes.STANDARD, Axes.SWAPPED)

    /** The modes the launcher offers, in its order. */
    val MODES = listOf(Mode.OFF, Mode.ALWAYS, Mode.HOLD, Mode.TOGGLE)

    /** How the gyroscope is used until the player picks. */
    private val DEFAULT_MODE = Mode.HOLD

    class Button(val keyCode: Int, val label: String)

    /** The attack button: the game's Use action, read by the engine. Not a key code. */
    const val ATTACK = -1

    /** The button until the player picks. */
    private const val DEFAULT_BUTTON = ATTACK

    /** Buttons that can work the gyroscope. D-pad Down has no action of its own outside menus. */
    val BUTTONS = listOf(
        Button(ATTACK, "Attack"),
        Button(KeyEvent.KEYCODE_DPAD_DOWN, "D-pad Down"),
        Button(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3"),
        Button(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3"),
        Button(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
        Button(KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    )

    private const val PREFS = "gyro"
    private const val KEY_MODE = "mode"
    private const val KEY_BUTTON = "button"
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_SOURCE = "source"
    private const val KEY_AXES = "axes"
    private const val KEY_INVERT_HORIZONTAL = "invert_horizontal"
    private const val KEY_INVERT_VERTICAL = "invert_vertical"
    private const val INPUT = "[Input]"
    private const val ENABLE = "enable gyroscope"
    private const val HORIZONTAL = "gyro horizontal sensitivity"
    private const val HORIZONTAL_DEFAULT = "1.5"

    // Read on the input thread by onPadKey, set on the UI thread by start.
    @Volatile
    private var activeMode = Mode.ALWAYS

    @Volatile
    private var activeButton = KeyEvent.KEYCODE_DPAD_DOWN

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** How the gyroscope is used once on; kept apart from on/off, which lives in settings.cfg. */
    private fun storedMode(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString(KEY_MODE, null) ?: "") }
            .getOrNull()?.takeIf { it != Mode.OFF } ?: DEFAULT_MODE

    fun button(context: Context): Int = prefs(context).getInt(KEY_BUTTON, DEFAULT_BUTTON)

    fun source(context: Context): Source =
        runCatching { Source.valueOf(prefs(context).getString(KEY_SOURCE, null) ?: "") }.getOrDefault(Source.AUTO)

    fun setSource(context: Context, source: Source) {
        prefs(context).edit().putString(KEY_SOURCE, source.name).apply()
    }

    fun axes(context: Context): Axes =
        runCatching { Axes.valueOf(prefs(context).getString(KEY_AXES, null) ?: "") }.getOrDefault(Axes.AUTO)

    fun setAxes(context: Context, axes: Axes) {
        prefs(context).edit().putString(KEY_AXES, axes.name).apply()
    }

    /** Whether a controller's gyroscope turns the camera the other way left and right ([PadGyro]). */
    fun invertHorizontal(context: Context): Boolean = prefs(context).getBoolean(KEY_INVERT_HORIZONTAL, false)

    fun setInvertHorizontal(context: Context, invert: Boolean) {
        prefs(context).edit().putBoolean(KEY_INVERT_HORIZONTAL, invert).apply()
    }

    /** Whether a controller's gyroscope turns the camera the other way up and down ([PadGyro]). */
    fun invertVertical(context: Context): Boolean = prefs(context).getBoolean(KEY_INVERT_VERTICAL, false)

    fun setInvertVertical(context: Context, invert: Boolean) {
        prefs(context).edit().putBoolean(KEY_INVERT_VERTICAL, invert).apply()
    }

    /** The buttons [mode] can use: the attack button holds the gyroscope on, it does not switch it. */
    fun buttons(mode: Mode): List<Button> = if (mode == Mode.HOLD) BUTTONS else BUTTONS.filter { it.keyCode != ATTACK }

    /** The button [mode] works with: D-pad Down where the attack button cannot be used. */
    fun buttonFor(stored: Int, mode: Mode): Int =
        if (stored == ATTACK && mode != Mode.HOLD) KeyEvent.KEYCODE_DPAD_DOWN else stored

    fun buttonLabel(keyCode: Int): String = BUTTONS.firstOrNull { it.keyCode == keyCode }?.label ?: "?"

    /** The mode as the launcher shows it: [Mode.OFF] while settings.cfg has the gyroscope off. */
    suspend fun mode(context: Context): Mode = withContext(Dispatchers.IO) {
        val file = File(Constants.SETTINGS_FILE)
        val on = runCatching { getSettingInSection(file.readLines(), INPUT, ENABLE) }.getOrNull() == "true"
        if (on) storedMode(context) else Mode.OFF
    }

    /**
     * Turn the gyroscope on in [DEFAULT_MODE], once, for a player who has never picked a mode.
     * Waits for settings.cfg to exist, so a first start is not missed.
     */
    suspend fun applyDefault(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_CHOSEN, false) || prefs.contains(KEY_MODE)) return
        if (!File(Constants.SETTINGS_FILE).exists()) return
        setMode(context, DEFAULT_MODE)
    }

    /** Forget the player's choice and turn the gyroscope on in [DEFAULT_MODE] again. */
    suspend fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        setMode(context, DEFAULT_MODE)
    }

    /** Store [mode], turning the engine's gyroscope on or off to match. Call with the game closed. */
    suspend fun setMode(context: Context, mode: Mode) = withContext(Dispatchers.IO) {
        prefs(context).edit().apply {
            if (mode != Mode.OFF) putString(KEY_MODE, mode.name)
            putBoolean(KEY_CHOSEN, true)
        }.apply()
        val file = File(Constants.SETTINGS_FILE)
        runCatching {
            if (!file.exists()) return@runCatching
            val original = file.readLines()
            val lines = original.toMutableList()
            setSettingInSection(lines, INPUT, ENABLE, if (mode == Mode.OFF) "false" else "true")
            // Turning a handheld left and right is a smaller movement than tilting it, so the
            // horizontal speed starts higher. Only while it is still the engine's own default.
            if (mode != Mode.OFF) {
                val horizontal = getSettingInSection(lines, INPUT, HORIZONTAL)?.toFloatOrNull()
                if (horizontal == null || horizontal == 1.0f) {
                    setSettingInSection(lines, INPUT, HORIZONTAL, HORIZONTAL_DEFAULT)
                }
            }
            if (lines != original) file.writeText(lines.joinToString("\n"))
        }.onFailure { Log.w(TAG, "Could not write $ENABLE", it) }
    }

    fun setButton(context: Context, keyCode: Int) {
        prefs(context).edit().putInt(KEY_BUTTON, keyCode).apply()
    }

    /** Push the choice to the engine as the game starts. */
    fun start(context: Context) {
        activeMode = storedMode(context)
        activeButton = buttonFor(button(context), activeMode)
        keyDown = false
        hatDown = false
        touches = 0
        buttonDown = false
        runCatching {
            EngineActivity.setGyroMode(activeMode.engineValue)
            EngineActivity.setGyroOnAttack(activeButton == ATTACK)
            EngineActivity.setGyroPhoneOnly(source(context) == Source.PHONE)
            EngineActivity.gyroButton(false)
        }.onFailure { Log.e(TAG, "setGyroMode failed", it) }
        Log.i(TAG, "mode $activeMode, button ${buttonLabel(activeButton)}")
    }

    // The chosen button as a key and, for D-pad Down, as the hat. A D-pad may report itself both
    // ways, and every stick movement reports the hat too, so the two are kept apart and a press is
    // passed on only when the button as a whole goes down or up, never twice.
    @Volatile
    private var keyDown = false

    @Volatile
    private var hatDown = false

    // Fingers now on the parts of the touch controller that hold the gyroscope on, see [touch].
    @Volatile
    private var touches = 0

    @Volatile
    private var buttonDown = false

    private fun update() {
        val down = keyDown || hatDown || (activeMode == Mode.HOLD && touches > 0)
        if (down == buttonDown) return
        buttonDown = down
        if (activeMode != Mode.HOLD && activeMode != Mode.TOGGLE) return
        runCatching { EngineActivity.gyroButton(down) }
    }

    /** A controller key on its way to the game. Passes on the chosen button's presses. */
    @JvmStatic
    fun onPadKey(keyCode: Int, event: KeyEvent) {
        if (keyCode != activeButton) return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyDown = true
            KeyEvent.ACTION_UP -> keyDown = false
            else -> return
        }
        update()
    }

    /** The first hat's up/down on its way to the game: the D-pad on controllers that send it so. */
    @JvmStatic
    fun onPadHat(hatY: Int) {
        if (activeButton != KeyEvent.KEYCODE_DPAD_DOWN) return
        hatDown = hatY > 0
        update()
    }

    /**
     * A finger went down on, or up from, a part of the touch controller that holds the gyroscope on
     * in [Mode.HOLD], as the button does: the look pad. The attack button is the game's Use, which the
     * engine reads itself when it is the gyroscope's button ([ATTACK]).
     */
    fun touch(down: Boolean) {
        touches = (touches + if (down) 1 else -1).coerceAtLeast(0)
        update()
    }
}
