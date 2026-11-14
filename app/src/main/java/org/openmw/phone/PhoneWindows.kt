package org.openmw.phone

import android.content.Context
import android.util.Log
import org.openmw.Constants
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Places the game's windows between the two sides of the touch controller on the phone alone, so
 * the controller does not cover them while a menu is open ([GameScreenPad]).
 *
 * The windows keep their layout and are only squeezed sideways into the middle: each window's `x`
 * and `w` in `[Windows]` of settings.cfg (and the same for its maximized size) are mapped from the
 * whole width onto the part between the sides. The sides are not equally wide, so the middle is a
 * little off centre. The height is left as it is.
 *
 * Some windows have a minimum width in the engine, and a window made narrower than that grows back
 * to the right, over the right side. Those are made as wide as their minimum and moved left to
 * stay clear of it. The minimum is in the engine's GUI units, so it takes the GUI canvas width
 * (`[Video] resolution x` over `[GUI] scaling factor`).
 *
 * A window the player moved or resized is theirs and keeps its place, unless it is under a side:
 * then it is moved out from under it, keeping its size where it fits. Any other window, still where
 * the engine or this app puts it or where this did last time, is placed as above. With an external
 * screen connected the windows go back to the whole width. The shares of the screen used last time
 * are kept in the app's preferences, so the windows can be told apart and moved again on another
 * phone or with other widths of the sides.
 *
 * Written before the game starts, which is when the engine reads them.
 */
internal object PhoneWindows {
    private const val TAG = "PhoneWindows"
    private const val PREFS = "phone_windows"
    private const val KEY_LEFT = "left_fraction"
    private const val KEY_RIGHT = "right_fraction"
    private const val KEY_CANVAS = "canvas_width"

    /** The first version, with both sides equally wide: read when the two keys above are not there yet. */
    private const val KEY_SIDE = "side_fraction"
    private const val SECTION = "[Windows]"

    /** Values closer than this are taken as equal: the engine writes them back with its own rounding. */
    private const val SAME = 0.0005f

    /**
     * A window's horizontal place: `x` and `w`, and the same when maximized. [minW] is the
     * window's minimum width in the engine's GUI units (MinSize in its .layout file).
     */
    private class Place(
        val x: Float, val w: Float, val maxX: Float = 0.015f, val maxW: Float = 0.97f, val minW: Float = 0f,
    )

    /** The engine's defaults (settings-default.cfg). */
    private val ENGINE = mapOf(
        "stats" to Place(0.015f, 0.4275f, 0.255f, 0.4275f, minW = 244f),
        "spells" to Place(0.63f, 0.36f, 0.32f, 0.36f),
        "map" to Place(0.63f, 0.36f),
        "inventory" to Place(0.015f, 0.45f),
        "inventory container" to Place(0.015f, 0.45f),
        "inventory barter" to Place(0.015f, 0.45f),
        "inventory companion" to Place(0.015f, 0.45f),
        "dialogue" to Place(0.15f, 0.7f, minW = 380f),
        "alchemy" to Place(0.25f, 0.5f, minW = 425f),
        "console" to Place(0.255f, 0.49f),
        "container" to Place(0.49f, 0.39f, minW = 245f),
        "barter" to Place(0.6f, 0.38f, minW = 428f),
        "companion" to Place(0.6f, 0.38f, minW = 245f),
        "settings" to Place(0.1f, 0.8f, minW = 640f),
    )

    /** Where this app puts a window instead of the engine (settings.fallback.cfg, seedConsoleWindowSize). */
    private val APP = mapOf(
        "dialogue" to Place(0.0635417f, 0.871875f),
        "console" to Place(0.02f, 0.96f),
    )

    /**
     * The part of the width left of the windows and right of them, and the GUI canvas width in the
     * engine's units (0 when not known, which leaves minimum widths out).
     */
    private class Sides(val left: Float, val right: Float, val canvas: Float) {
        val none get() = left == 0f && right == 0f

        /**
         * A place across the whole width, moved onto the part between the sides: `x` to `w`. A
         * window narrower than [minW] is made that wide and moved left as far as needed to end at
         * the right side, but not past the left one.
         */
        fun place(x: Float, w: Float, minW: Float): Pair<Float, Float> {
            var nx = left + x * (1f - left - right)
            var nw = w * (1f - left - right)
            if (none || canvas <= 0f || minW <= 0f) return nx to nw
            nw = maxOf(nw, minW / canvas)
            val end = 1f - right
            if (nx + nw > end) nx = maxOf(left, end - nw)
            return nx to nw
        }

        /**
         * A window the player placed, moved out from under the sides if it is under one: its size is
         * kept where it fits between them. Null when it is clear of them already, or there are none.
         */
        fun keepClear(x: Float, w: Float, minW: Float): Pair<Float, Float>? {
            if (none) return null
            val end = 1f - right
            val shown = if (canvas > 0f) maxOf(w, minW / canvas) else w
            if (x >= left - SAME && x + shown <= end + SAME) return null
            val nw = minOf(shown, end - left)
            // Not coerceIn: with the window as wide as the room, rounding can leave end - nw below left.
            return maxOf(left, minOf(x, end - nw)) to nw
        }
    }

    /**
     * Called by the game activity at start. [left] and [right] are the sides' shares of the width,
     * both 0 for the whole width.
     */
    fun fit(context: Context, left: Float, right: Float) {
        if (Constants.SETTINGS_FILE.isEmpty()) return
        val file = File(Constants.SETTINGS_FILE)
        if (!file.exists()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldSide = prefs.getFloat(KEY_SIDE, 0f)
        val last = Sides(prefs.getFloat(KEY_LEFT, oldSide), prefs.getFloat(KEY_RIGHT, oldSide), prefs.getFloat(KEY_CANVAS, 0f))

        val original = runCatching { file.readLines() }.getOrElse { return }
        val sides = Sides(left, right, canvasWidth(original))
        if (sides.none && last.none) return
        val lines = original.toMutableList()
        for ((name, engine) in ENGINE) {
            val base = APP[name] ?: engine
            val minW = engine.minW
            fitPair(lines, "$name x", "$name w", engine.x, engine.w, base.x, base.w, minW, last, sides)
            fitPair(lines, "$name maximized x", "$name maximized w", engine.maxX, engine.maxW, base.maxX, base.maxW, minW, last, sides)
        }
        if (lines != original) {
            val written = runCatching { file.writeText(lines.joinToString("\n")) }
                .onFailure { Log.e(TAG, "could not write ${file.path}", it) }
                .isSuccess
            if (!written) return
            Log.i(TAG, "windows fitted between the sides: $left of the width left, $right right")
        }
        prefs.edit().putFloat(KEY_LEFT, left).putFloat(KEY_RIGHT, right).putFloat(KEY_CANVAS, sides.canvas)
            .remove(KEY_SIDE).apply()
    }

    /** The GUI canvas width in the engine's units, or 0 when the file does not say. */
    private fun canvasWidth(lines: List<String>): Float {
        val width = SettingsLines.get(lines, "[Video]", "resolution x")?.toFloatOrNull() ?: return 0f
        val scaling = SettingsLines.get(lines, "[GUI]", "scaling factor")?.toFloatOrNull() ?: return 0f
        return if (width > 0f && scaling > 0f) width / scaling else 0f
    }

    /**
     * Moves one window's `x` and `w` between [sides], if both are still at the engine's or the
     * app's value, or where [last] put them. Missing keys are at the engine's default.
     */
    private fun fitPair(
        lines: MutableList<String>, keyX: String, keyW: String,
        engineX: Float, engineW: Float, baseX: Float, baseW: Float, minW: Float,
        last: Sides, sides: Sides,
    ) {
        val x = get(lines, keyX) ?: engineX
        val w = get(lines, keyW) ?: engineW
        val untouched = listOf(
            engineX to engineW,
            baseX to baseW,
            last.place(baseX, baseW, minW),
        ).any { (ux, uw) -> abs(x - ux) < SAME && abs(w - uw) < SAME }
        val (newX, newW) = if (untouched) sides.place(baseX, baseW, minW) else sides.keepClear(x, w, minW) ?: return
        if (abs(x - newX) < SAME && abs(w - newW) < SAME) return
        set(lines, keyX, format(newX))
        set(lines, keyW, format(newW))
    }

    private fun format(v: Float) = String.format(Locale.US, "%.6f", v)

    private fun get(lines: List<String>, key: String): Float? = SettingsLines.get(lines, SECTION, key)?.toFloatOrNull()

    private fun set(lines: MutableList<String>, key: String, value: String) = SettingsLines.set(lines, SECTION, key, value)
}
