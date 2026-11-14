package org.openmw.phone

import android.content.Context
import android.util.Log
import org.openmw.Constants
import java.io.File

/**
 * Settings the phone build starts with where they differ from the ones tuned for the Duo Lite
 * (applyTunedPerformanceSettings in UITools.kt), which both builds share. Phones have more GPU to
 * spare than the Duo Lite.
 *
 * Written once into settings.cfg before the game starts, each only where it still has the Duo Lite's
 * value, so a value the player set is kept. [VERSION] is the last change applied; bump it with each
 * new one.
 *
 * v1: `[Water] reflection detail = 1` where it is 0: the land shows in water reflections, not only
 *     the sky.
 */
internal object PhoneDefaults {
    private const val TAG = "PhoneDefaults"
    private const val PREFS = "phone_defaults"
    private const val KEY_VERSION = "version"
    private const val VERSION = 1

    /** Called by the game activity at start. */
    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_VERSION, 0)
        if (applied >= VERSION) return
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
