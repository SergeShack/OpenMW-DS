package org.openmw.phone

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject
import org.openmw.BuildConfig
import org.openmw.Constants
import java.io.File

/**
 * Saved layouts of the touch controller, to switch between, for example one per set of mods.
 *
 * A layout holds everything the editor sets, on every [PadSurface]: where the controller's parts
 * are and which are hidden, the key buttons, the opacity and the look speed. It does not hold
 * whether the controller is hidden.
 *
 * Each layout is a file in the `layouts` folder of the app's own folder, named after it. The files
 * stay when the app is reinstalled, and can be copied to another phone or shared: one put into the
 * folder shows up in the list. What is in use is still kept in the app's settings ([PadLayout],
 * [KeyButtonLayout], [LookSpeed]); loading a layout copies it there.
 */
internal object PadPresets {
    private const val TAG = "PadPresets"
    private const val FORMAT = 1
    private const val EXTENSION = "json"

    /** Characters a name may not have, as it is a file name too. */
    private val NOT_IN_NAME = Regex("""[\\/:*?"<>|]""")

    /** The folder as the player finds it on the phone. */
    val FOLDER_LABEL = "${BuildConfig.STORAGE_DIR}/layouts"

    private fun folder() = File(Constants.USER_FILE_STORAGE, "layouts")

    private fun file(name: String) = File(folder(), "$name.$EXTENSION")

    /** The saved layouts' names, in alphabetical order. */
    fun names(): List<String> =
        folder().listFiles { f -> f.isFile && f.extension.equals(EXTENSION, ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()

    /** [name] made fit for a file name; empty if nothing is left. */
    fun cleanName(name: String): String = name.replace(NOT_IN_NAME, "").trim().take(60)

    fun exists(name: String) = file(name).isFile

    /** Saves the layout in use as [name], over a layout of that name if there is one. */
    fun save(context: Context, name: String): Boolean = runCatching {
        val json = JSONObject().put("format", FORMAT)
            .put("opacity", PadLayout.opacity(context).toDouble())
            .put("lookSpeed", LookSpeed.value.toDouble())
        val parts = JSONObject()
        val keys = JSONObject()
        for (surface in PadSurface.entries) {
            if (surface.hasParts) parts.put(surface.key, PadLayout.toJson(context, surface))
            KeyButtonLayout.toJson(context, surface)?.let { keys.put(surface.key, it) }
        }
        json.put("parts", parts).put("keys", keys)
        folder().mkdirs()
        file(name).writeText(json.toString(2))
        true
    }.onFailure { Log.e(TAG, "could not save layout $name", it) }.getOrDefault(false)

    /** Makes the layout [name] the one in use. */
    fun load(context: Context, name: String): Boolean = runCatching {
        val json = JSONObject(file(name).readText())
        PadLayout.saveOpacity(context, json.optDouble("opacity", PadLayout.OPACITY_DEFAULT.toDouble()).toFloat())
        LookSpeed.save(context, json.optDouble("lookSpeed", LookSpeed.DEFAULT.toDouble()).toFloat())
        val parts = json.optJSONObject("parts")
        val keys = json.optJSONObject("keys")
        for (surface in PadSurface.entries) {
            if (surface.hasParts) PadLayout.fromJson(context, surface, parts?.optJSONObject(surface.key))
            KeyButtonLayout.fromJson(context, surface, keys?.optJSONObject(surface.key))
        }
        true
    }.onFailure { Log.e(TAG, "could not load layout $name", it) }.getOrDefault(false)

    fun rename(from: String, to: String): Boolean = file(from).renameTo(file(to))

    fun delete(name: String): Boolean = file(name).delete()

    /**
     * Asks for a layout's name in a dialog, starting with [initial], and passes on a name that can be
     * used. A dialog rather than a field over the game, so the keyboard types into it and nothing
     * reaches the game.
     */
    fun askName(context: Context, title: String, initial: String, onName: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val box = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val name = cleanName(field.text.toString())
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks before something that cannot be taken back, and runs [onYes] if the player agrees. */
    fun confirm(context: Context, message: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
