package org.openmw.phone

import android.content.Context
import android.util.Log
import org.openmw.Constants
import org.openmw.companion.UiPreferences
import java.io.File

/**
 * Settings the phone build starts with where they differ from the ones tuned for the Duo Lite
 * (applyTunedPerformanceSettings in UITools.kt), which both builds share. Phones have more GPU to
 * spare than the Duo Lite.
 *
 * Written once before the game starts, each only where it still has the Duo Lite's value, so a
 * value the player set is kept. [VERSION] is the last change applied; bump it with each new one.
 *
 * v1: `[Water] reflection detail = 1` where it is 0: the land shows in water reflections, not only
 *     the sky.
 * v2: the companion's inventory cards Large (3 rows) where Card Size was never set. Framed on a
 *     phone the companion is lower than the bottom screen the cards are made for, and with 4 rows
 *     their text comes out very small.
 * v3: the companion's Text size 105% and Page text size 105% where never set (120% and 115% on the
 *     Duo Lite). A dp on a phone is larger than on the handhelds' bottom screens, and the framed
 *     companion is lower, so at those sizes the pages crowded and the top stat bar wrapped.
 */
internal object PhoneDefaults {
    private const val TAG = "PhoneDefaults"

    /** Screen height the shipped `[GUI] scaling factor` is meant for, in pixels. */
    private const val GUI_DESIGN_HEIGHT = 1080f

    /**
     * How much larger the game's windows are drawn on a game screen [nativeHeight] pixels high.
     *
     * The shipped `[GUI] scaling factor` is meant for a 1080 pixel high screen, as the Duo Lite's is.
     * Phones are about as high in centimetres but often have more pixels, 1440 or more, and at the
     * same scaling the windows and their text come out a quarter smaller or worse. Scaled by the
     * height, the windows take the same part of the screen on any phone, and the same on a 4K TV as
     * on a 1080p one. Interface size in the launcher still applies on top.
     */
    fun guiScreenScale(nativeHeight: Int): Float =
        if (nativeHeight > 0) nativeHeight / GUI_DESIGN_HEIGHT else 1f
    private const val PREFS = "phone_defaults"
    private const val KEY_VERSION = "version"
    private const val VERSION = 3

    /** Companion Text size and Page text size on a phone. */
    private const val TEXT_SIZE = 1.05f
    private const val PAGE_TEXT_SIZE = 1.05f

    /** Card Size Large: the inventory card grid's row count. */
    private const val INV_CARD_ROWS_LARGE = 3

    /** Called by the game activity at start. */
    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_VERSION, 0)
        if (applied >= VERSION) return
        if (applied < 2) {
            // UiPreferences' file and key; contains() tells a value the player set from the default.
            val ui = context.getSharedPreferences("companion_ui_settings", Context.MODE_PRIVATE)
            if (!ui.contains("inventory_card_rows")) UiPreferences.setInvCardRows(context, INV_CARD_ROWS_LARGE)
        }
        if (applied < 3) {
            val ui = context.getSharedPreferences("companion_ui_settings", Context.MODE_PRIVATE)
            if (!ui.contains("text_size")) UiPreferences.setTextSize(context, TEXT_SIZE)
            if (!ui.contains("page_text_size")) UiPreferences.setPageTextSize(context, PAGE_TEXT_SIZE)
        }
        if (Constants.SETTINGS_FILE.isEmpty()) return
        val file = File(Constants.SETTINGS_FILE)
        // Not there yet: tried again at the next start.
        if (!file.exists()) return

        val original = runCatching { file.readLines() }.getOrElse { return }
        val lines = original.toMutableList()
        if (applied < 1 && SettingsLines.get(lines, "[Water]", "reflection detail") == "0") {
            SettingsLines.set(lines, "[Water]", "reflection detail", "1")
        }
        if (lines != original) {
            val written = runCatching { file.writeText(lines.joinToString("\n")) }
                .onFailure { Log.e(TAG, "could not write ${file.path}", it) }
                .isSuccess
            if (!written) return
            Log.i(TAG, "phone defaults applied: v${applied + 1}..v$VERSION")
        }
        prefs.edit().putInt(KEY_VERSION, VERSION).apply()
    }
}
