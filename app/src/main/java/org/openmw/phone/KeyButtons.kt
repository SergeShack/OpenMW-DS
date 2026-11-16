package org.openmw.phone

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.libsdl.app.SDLActivity
import kotlin.math.abs

/**
 * A button of the touch controller that presses one keyboard key, for mods that are played with
 * keys of their own. The player adds them in the editor ([PadEditor]) and places them like the
 * other parts.
 *
 * [place] is in dp on the screen it is shown on.
 */
internal data class KeyButton(val id: Long, val keyCode: Int, val place: PartPlace)

/**
 * The key buttons the player made, kept per [PadSurface].
 *
 * A button is kept as the area of the surface it is in, its centre as a share of that area and its
 * size in dp, so the same layout fits a phone with another screen size, as [PadLayout] does.
 */
internal object KeyButtonLayout {
    private const val TAG = "KeyButtonLayout"
    private const val PREFS = "phone_key_buttons"
    private const val FORMAT = 1

    /** Size of a new key button, in dp. */
    const val NEW_W = 64f
    const val NEW_H = 48f

    /** Room kept around a new key button, and the step its place is looked for in, in dp. */
    private const val NEW_GAP = 8f

    fun load(context: Context, surface: PadSurface, areas: List<PadArea>): List<KeyButton> {
        if (areas.isEmpty()) return emptyList()
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(surface.key, null)
            ?: return emptyList()
        return runCatching { fromJson(JSONObject(text), areas) }
            .onFailure { Log.e(TAG, "could not read the ${surface.key} key buttons", it) }
            .getOrDefault(emptyList())
    }

    fun save(context: Context, surface: PadSurface, buttons: List<KeyButton>, areas: List<PadArea>) {
        if (areas.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(surface.key, toJson(buttons, areas).toString())
            .apply()
    }

    /**
     * A new button for [keyCode] in [area], where it covers none of [taken]: the free place nearest
     * the top of the area, and of those the one nearest its middle. In the middle if there is none.
     */
    fun newButton(existing: List<KeyButton>, keyCode: Int, area: PadArea, taken: List<PartPlace>): KeyButton {
        val id = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val middle = PartPlace(area.x + (area.w - NEW_W) / 2, area.y + (area.h - NEW_H) / 2, NEW_W, NEW_H)
        return KeyButton(id, keyCode, (freePlace(area, taken) ?: middle).inside(area))
    }

    private fun freePlace(area: PadArea, taken: List<PartPlace>): PartPlace? {
        val xs = generateSequence(area.x + NEW_GAP) { it + NEW_GAP }.takeWhile { it + NEW_W + NEW_GAP <= area.right }
            .sortedBy { abs(it + NEW_W / 2 - (area.x + area.w / 2)) }
            .toList()
        var y = area.y + NEW_GAP
        while (y + NEW_H + NEW_GAP <= area.bottom) {
            for (x in xs) {
                val place = PartPlace(x, y, NEW_W, NEW_H)
                if (taken.none { it.overlaps(place, NEW_GAP) }) return place
            }
            y += NEW_GAP
        }
        return null
    }

    private fun PartPlace.overlaps(other: PartPlace, gap: Float) =
        x < other.x + other.w + gap && other.x < x + w + gap && y < other.y + other.h + gap && other.y < y + h + gap

    private fun toJson(buttons: List<KeyButton>, areas: List<PadArea>): JSONObject {
        val list = JSONArray()
        for (b in buttons) {
            val area = PadArea.nearest(areas, b.place.centerX, b.place.centerY)
            list.put(
                JSONObject()
                    .put("id", b.id)
                    .put("key", b.keyCode)
                    .put("area", areas.indexOf(area))
                    .put("cx", ((b.place.centerX - area.x) / area.w).toDouble())
                    .put("cy", ((b.place.centerY - area.y) / area.h).toDouble())
                    .put("w", b.place.w.toDouble())
                    .put("h", b.place.h.toDouble())
            )
        }
        return JSONObject().put("format", FORMAT).put("buttons", list)
    }

    private fun fromJson(json: JSONObject, areas: List<PadArea>): List<KeyButton> {
        val list = json.optJSONArray("buttons") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val keyCode = o.optInt("key", KeyEvent.KEYCODE_UNKNOWN)
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return@mapNotNull null
            val area = areas[o.optInt("area", 0).coerceIn(0, areas.size - 1)]
            val w = o.optDouble("w", NEW_W.toDouble()).toFloat()
            val h = o.optDouble("h", NEW_H.toDouble()).toFloat()
            val cx = area.x + o.optDouble("cx", 0.5).toFloat() * area.w
            val cy = area.y + o.optDouble("cy", 0.5).toFloat() * area.h
            KeyButton(o.optLong("id", i + 1L), keyCode, PartPlace(cx - w / 2, cy - h / 2, w, h).inside(area))
        }
    }
}

/** The key buttons, each where the player put it. */
@Composable
internal fun PlacedKeys(keys: List<KeyButton>) {
    Box(Modifier.fillMaxSize()) {
        for (button in keys) {
            key(button.id) {
                KeyButtonView(
                    button.keyCode,
                    Modifier.offset(button.place.x.dp, button.place.y.dp).size(button.place.w.dp, button.place.h.dp),
                )
            }
        }
    }
}

/**
 * A key button as it works in the game: the key is held for as long as a finger is on it. It goes
 * to the game the way a real keyboard's keys do, so the game's and the mods' own bindings apply.
 */
@Composable
internal fun KeyButtonView(keyCode: Int, modifier: Modifier) {
    PadButton(KeyNames.label(keyCode), modifier, labelSize = KeyNames.labelSize(keyCode)) { pressed ->
        runCatching {
            if (pressed) SDLActivity.onNativeKeyDown(keyCode) else SDLActivity.onNativeKeyUp(keyCode)
        }
    }
}
