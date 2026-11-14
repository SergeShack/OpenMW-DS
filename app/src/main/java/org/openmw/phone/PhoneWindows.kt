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
 * A window the player moved or resized is theirs and is left alone: only a window still where the
 * engine or this app puts it, or where this did last time, is changed. With an external screen
 * connected the windows go back to the whole width. The shares of the screen used last time are
 * kept in the app's preferences, so the windows can be told apart and moved again on another phone
 * or with other widths of the sides.
 *
 * Written before the game starts, which is when the engine reads them.
 */
internal object PhoneWindows {
    private const val TAG = "PhoneWindows"
    private const val PREFS = "phone_windows"
    private const val KEY_LEFT = "left_fraction"
    private const val KEY_RIGHT = "right_fraction"

    /** The first version, with both sides equally wide: read when the two keys above are not there yet. */
    private const val KEY_SIDE = "side_fraction"
    private const val SECTION = "[Windows]"

    /** Values closer than this are taken as equal: the engine writes them back with its own rounding. */
    private const val SAME = 0.0005f

    /** A window's horizontal place: `x` and `w`, and the same when maximized. */
    private class Place(val x: Float, val w: Float, val maxX: Float = 0.015f, val maxW: Float = 0.97f)

    /** The engine's defaults (settings-default.cfg). */
    private val ENGINE = mapOf(
        "stats" to Place(0.015f, 0.4275f, 0.255f, 0.4275f),
        "spells" to Place(0.63f, 0.36f, 0.32f, 0.36f),
        "map" to Place(0.63f, 0.36f),
        "inventory" to Place(0.015f, 0.45f),
        "inventory container" to Place(0.015f, 0.45f),
        "inventory barter" to Place(0.015f, 0.45f),
        "inventory companion" to Place(0.015f, 0.45f),
        "dialogue" to Place(0.15f, 0.7f),
        "alchemy" to Place(0.25f, 0.5f),
        "console" to Place(0.255f, 0.49f),
        "container" to Place(0.49f, 0.39f),
        "barter" to Place(0.6f, 0.38f),
        "companion" to Place(0.6f, 0.38f),
        "settings" to Place(0.1f, 0.8f),
    )

    /** Where this app puts a window instead of the engine (settings.fallback.cfg, seedConsoleWindowSize). */
    private val APP = mapOf(
        "dialogue" to Place(0.0635417f, 0.871875f),
        "console" to Place(0.02f, 0.96f),
    )

    /** The part of the width left of the windows and right of them. */
    private class Sides(val left: Float, val right: Float) {
        val none get() = left == 0f && right == 0f

        /** A place across the whole width, moved onto the part between the sides. */
        fun x(x: Float) = left + x * (1f - left - right)
        fun w(w: Float) = w * (1f - left - right)
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
        val last = Sides(prefs.getFloat(KEY_LEFT, oldSide), prefs.getFloat(KEY_RIGHT, oldSide))
        val sides = Sides(left, right)
        if (sides.none && last.none) return

        val original = runCatching { file.readLines() }.getOrElse { return }
        val lines = original.toMutableList()
        for ((name, engine) in ENGINE) {
            val base = APP[name] ?: engine
            fitPair(lines, "$name x", "$name w", engine.x, engine.w, base.x, base.w, last, sides)
            fitPair(lines, "$name maximized x", "$name maximized w", engine.maxX, engine.maxW, base.maxX, base.maxW, last, sides)
        }
        if (lines != original) {
            val written = runCatching { file.writeText(lines.joinToString("\n")) }
                .onFailure { Log.e(TAG, "could not write ${file.path}", it) }
                .isSuccess
            if (!written) return
            Log.i(TAG, "windows fitted between the sides: $left of the width left, $right right")
        }
        prefs.edit().putFloat(KEY_LEFT, left).putFloat(KEY_RIGHT, right).remove(KEY_SIDE).apply()
    }

    /**
     * Moves one window's `x` and `w` between [sides], if both are still at the engine's or the
     * app's value, or where [last] put them. Missing keys are at the engine's default.
     */
    private fun fitPair(
        lines: MutableList<String>, keyX: String, keyW: String,
        engineX: Float, engineW: Float, baseX: Float, baseW: Float,
        last: Sides, sides: Sides,
    ) {
        val x = get(lines, keyX) ?: engineX
        val w = get(lines, keyW) ?: engineW
        val untouched = listOf(
            engineX to engineW,
            baseX to baseW,
            last.x(baseX) to last.w(baseW),
        ).any { (ux, uw) -> abs(x - ux) < SAME && abs(w - uw) < SAME }
        if (!untouched) return
        val newX = sides.x(baseX)
        val newW = sides.w(baseW)
        if (abs(x - newX) < SAME && abs(w - newW) < SAME) return
        set(lines, keyX, format(newX))
        set(lines, keyW, format(newW))
    }

    private fun format(v: Float) = String.format(Locale.US, "%.6f", v)

    private fun keyOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || !trimmed.contains('=')) return null
        return trimmed.substringBefore('=').trim()
    }

    private fun isSection(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("[") && trimmed.endsWith("]")
    }

    private fun sectionStart(lines: List<String>) = lines.indexOfFirst { it.trim().equals(SECTION, ignoreCase = true) }

    private fun get(lines: List<String>, key: String): Float? {
        val start = sectionStart(lines)
        if (start < 0) return null
        for (i in start + 1 until lines.size) {
            if (isSection(lines[i])) break
            if (keyOf(lines[i]).equals(key, ignoreCase = true)) {
                return lines[i].substringAfter('=').trim().toFloatOrNull()
            }
        }
        return null
    }

    private fun set(lines: MutableList<String>, key: String, value: String) {
        var start = sectionStart(lines)
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(SECTION)
            start = lines.lastIndex
        }
        for (i in start + 1 until lines.size) {
            if (isSection(lines[i])) break
            if (keyOf(lines[i]).equals(key, ignoreCase = true)) {
                lines[i] = "$key = $value"
                return
            }
        }
        lines.add(start + 1, "$key = $value")
    }
}
