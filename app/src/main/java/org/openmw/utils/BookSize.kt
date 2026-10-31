package org.openmw.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openmw.Constants
import java.io.File
import kotlin.math.roundToInt

/** Vanilla book size, and the value that removes the override. */
const val BOOK_SIZE_VANILLA = 100

/** Book size a fresh install starts on. */
const val BOOK_SIZE_DEFAULT = 125

/**
 * The Book size choices, in percent. 125 is the largest that still fits a 16:9 screen: the book's
 * art is 728 x 398 layer units, so the scale can't go past `screen height / 398`, which from
 * 600 x 520 is ~127%.
 */
val BOOK_SIZE_OPTIONS = listOf(100, 110, 120, 125)

/** The engine's own height of the `JournalBooks` layer, in `files/data/mygui/openmw_layers.xml`. */
private const val VANILLA_BOOK_LAYER_HEIGHT = 520

private val bookLayerSize = Regex(
    """(<Layer\s+name="JournalBooks"[^>]*>\s*<Property\s+key="Size"\s+value=")(\d+)\s+(\d+)(")"""
)

/**
 * Write (or remove) the `mygui/openmw_layers.xml` override that sets the Book size.
 *
 * Books, scrolls and the journal are drawn on the `JournalBooks` layer, a MyGUI ScalingLayer with
 * a fixed 600 x 520 canvas stretched to fit the screen. `[GUI] scaling factor` doesn't affect it,
 * so to draw them bigger the canvas is made shorter: 520 / 1.25 = 416 draws them 25% larger. The
 * book fonts are sized from the same file (`bookScale` in fontloader.cpp), so the text stays sharp
 * and the line breaks don't change.
 *
 * The override goes in the companion mod folder and is a copy of the engine's file with only that
 * layer changed. The engine reads it only at startup, so a change applies on the next start.
 */
suspend fun Context.applyBookSize() {
    val percent = GameFilesPreferences.loadBookSize(this).first()
    withContext(Dispatchers.IO) {
        val override = File("${Constants.USER_FILE_STORAGE}/OpenMW/Mods/companion/mygui/openmw_layers.xml")
        runCatching {
            if (percent == BOOK_SIZE_VANILLA) {
                override.delete()
                return@runCatching
            }
            val base = File("${Constants.USER_RESOURCES}/vfs/mygui/openmw_layers.xml")
            val xml = base.readText()
            val height = (VANILLA_BOOK_LAYER_HEIGHT * 100f / percent).roundToInt()
            val patched = bookLayerSize.replace(xml) { m ->
                "${m.groupValues[1]}${m.groupValues[2]} $height${m.groupValues[4]}"
            }
            if (patched == xml) {
                // The engine's file changed shape. Leave the vanilla size rather than guess.
                Log.w("BookSize", "JournalBooks layer size not found in ${base.path}")
                override.delete()
                return@runCatching
            }
            override.parentFile?.mkdirs()
            override.writeText(patched)
        }.onFailure { Log.w("BookSize", "Could not apply book size $percent%", it) }
    }
}
