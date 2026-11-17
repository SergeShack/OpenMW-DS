package org.openmw.utils

import android.util.Log
import org.openmw.Constants
import java.io.File

/**
 * Mod profiles: named copies of the user `openmw.cfg`, so a player can keep several mod setups and
 * switch between them before starting the game, as the desktop launcher's content lists do.
 *
 * The user `openmw.cfg` stays the one live file. Everything that edits mods (both launchers, Mod
 * Assistant, the setup check) keeps writing it and knows nothing about profiles. A profile is just a
 * saved copy in `config/profiles/<name>.cfg`, and `config/profiles/active` holds the name of the
 * profile the live file belongs to.
 *
 * Switching first copies the live file back into the active profile, then copies the chosen
 * profile over the live file. So changes made to the mod list are never lost, and there is no
 * separate "save profile" step.
 *
 * Only the mod setup is in a profile. `settings.cfg` and the saves are shared by all profiles; the
 * one exception is the last character played, see [restoreCharacter].
 *
 * All functions touch files and should be called off the main thread.
 */
object ModProfiles {

    private const val TAG = "ModProfiles"
    private const val EXTENSION = ".cfg"
    private const val CHARACTER_EXTENSION = ".character"
    const val DEFAULT_NAME = "Default"
    const val MAX_NAME_LENGTH = 40

    /** Characters a file name cannot hold on Android's shared storage. */
    private val FORBIDDEN_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val dir get() = File(Constants.USER_CONFIG, "profiles")
    private val activeFile get() = File(dir, "active")
    private val liveFile get() = File(Constants.USER_OPENMW_CFG)

    private fun fileOf(name: String) = File(dir, name + EXTENSION)

    data class State(val names: List<String>, val active: String)

    /**
     * The profile list and the active profile. The first call makes a "Default" profile from the
     * current mod setup, so existing players start with what they have.
     */
    @Synchronized
    fun state(): State {
        ensureActive()
        return State(names(), readActive() ?: DEFAULT_NAME)
    }

    /** Why [name] cannot be used for a new or renamed profile, or null if it can. */
    fun nameProblem(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Enter a name."
            n.length > MAX_NAME_LENGTH -> "The name is too long."
            n.startsWith(".") -> "The name cannot start with a dot."
            n.any { it in FORBIDDEN_CHARS || it.isISOControl() } ->
                "The name cannot contain / \\ : * ? \" < > |"
            names().any { it.equals(n, ignoreCase = true) } -> "A profile with this name already exists."
            else -> null
        }
    }

    /** Save the live file into the active profile, then make [name] live. */
    @Synchronized
    fun switchTo(name: String): Boolean {
        ensureActive()
        val target = fileOf(name)
        if (!target.isFile) return false
        if (name == readActive()) return true
        return runCatching {
            saveActive()
            writeAtomically(liveFile, target.readBytes())
            writeActive(name)
            restoreCharacter(name)
            true
        }.onFailure { Log.e(TAG, "Switch to '$name' failed", it) }.getOrDefault(false)
    }

    /**
     * A new profile holding a copy of the current mod setup, made active. The live file does not
     * change, so the player starts from what they had and turns mods on or off from there.
     */
    @Synchronized
    fun create(name: String): Boolean {
        val n = name.trim()
        if (nameProblem(n) != null) return false
        ensureActive()
        return runCatching {
            saveActive()
            writeAtomically(fileOf(n), liveBytes())
            writeActive(n)
            // Same mods, so the same character for now.
            readCurrentCharacter()?.let { writeAtomically(characterFileOf(n), it.toByteArray()) }
            true
        }.onFailure { Log.e(TAG, "Create '$n' failed", it) }.getOrDefault(false)
    }

    @Synchronized
    fun rename(old: String, new: String): Boolean {
        val n = new.trim()
        if (n.equals(old, ignoreCase = true) && n != old) {
            // Only the case changes. Rename through a temporary name, since the old and new file
            // can be the same file on a case-insensitive file system.
            val tmp = File(dir, ".rename" + EXTENSION)
            if (!fileOf(old).renameTo(tmp) || !tmp.renameTo(fileOf(n))) return false
            val tmpChar = File(dir, ".rename" + CHARACTER_EXTENSION)
            if (characterFileOf(old).renameTo(tmpChar)) tmpChar.renameTo(characterFileOf(n))
        } else {
            if (nameProblem(n) != null) return false
            if (!fileOf(old).renameTo(fileOf(n))) return false
            characterFileOf(old).renameTo(characterFileOf(n))
        }
        if (readActive() == old) writeActive(n)
        return true
    }

    /** Delete a profile that is not active. The active one holds the live setup and stays. */
    @Synchronized
    fun delete(name: String): Boolean {
        if (name == readActive()) return false
        characterFileOf(name).delete()
        return fileOf(name).delete()
    }

    // ---- The last character played with each profile ----
    //
    // The game's Load window opens on `[Saves] character` in settings.cfg, the last character
    // played. Settings are shared by all profiles, so after a switch it would open on a character
    // made with other mods. Each profile therefore keeps its own last character in
    // `<name>.character`, saved when switching away from it and put back when switching to it.

    private fun characterFileOf(name: String) = File(dir, name + CHARACTER_EXTENSION)

    /** Keep the character of the profile being left, then set the one for [name]. */
    private fun restoreCharacter(name: String) {
        val settings = File(Constants.SETTINGS_FILE)
        if (!settings.isFile) return
        val stored = characterFileOf(name).takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() && File(Constants.USER_SAVES, it).isDirectory }
        val character = stored ?: guessCharacter(fileOf(name)) ?: return
        if (character != readCurrentCharacter()) writeIniValue("Saves", "character", character, null)
    }

    /**
     * Called before the active profile changes, so its character is known when coming back. Only a
     * character with a save made with this profile's plugins is kept: the setting may still name a
     * character from before profiles existed, or one played with other mods.
     */
    private fun rememberCharacter() {
        val active = readActive() ?: return
        val character = readCurrentCharacter() ?: return
        val wanted = profilePlugins(fileOf(active))
        val saves = File(Constants.USER_SAVES, character).listFiles() ?: return
        if (saves.none { isSave(it) && savePlugins(it) == wanted }) return
        writeAtomically(characterFileOf(active), character.toByteArray())
    }

    private fun isSave(file: File) = file.isFile && file.name.endsWith(".omwsave", ignoreCase = true)

    /**
     * Plugins compared between a profile and a save. Script-only files (`.omwscripts`) are left
     * out, as the app adds some of its own outside the user config.
     */
    private fun plugins(names: List<String>) = names.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.endsWith(".omwscripts") }
        .toSet()

    /** The enabled plugins of a profile file. */
    private fun profilePlugins(profile: File): Set<String> =
        if (!profile.isFile) emptySet() else plugins(
            profile.readLines().map { it.trim() }.filter { it.startsWith("content=") }
                .map { it.removePrefix("content=") }
        )

    /** The plugins a save was made with: a save lists its content files in its header. */
    private fun savePlugins(save: File) = plugins(ModSetupCheck.readMasters(save))

    /** `[Saves] character` from settings.cfg, or null if it is not set. */
    private fun readCurrentCharacter(): String? {
        val settings = File(Constants.SETTINGS_FILE)
        if (!settings.isFile) return null
        var inSaves = false
        for (raw in settings.readLines()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inSaves = line.substring(1, line.length - 1).trim().equals("Saves", ignoreCase = true)
            } else if (inSaves && line.substringBefore("=").trim() == "character") {
                return line.substringAfter("=").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * For a profile with no character kept yet: the character with the latest save made with the
     * same plugins as the profile.
     */
    private fun guessCharacter(profile: File): String? {
        val wanted = profilePlugins(profile)
        if (wanted.isEmpty()) return null

        var best: String? = null
        var bestTime = Long.MIN_VALUE
        File(Constants.USER_SAVES).listFiles()?.filter { it.isDirectory }?.forEach { character ->
            val latest = character.listFiles()
                ?.filter { isSave(it) }
                ?.sortedByDescending { it.lastModified() }
                ?.firstOrNull { savePlugins(it) == wanted }
                ?: return@forEach
            if (latest.lastModified() > bestTime) {
                bestTime = latest.lastModified()
                best = character.name
            }
        }
        return best
    }

    /** Make sure there is an active profile and that its file exists. */
    private fun ensureActive() {
        dir.mkdirs()
        val active = readActive()
        if (active != null && fileOf(active).isFile) return
        // No profiles yet (first run), or the active one was removed by hand. The live file is
        // the setup in use, so it becomes the active profile.
        val name = active?.takeIf { nameProblem(it) == null } ?: freeName(DEFAULT_NAME)
        writeAtomically(fileOf(name), liveBytes())
        writeActive(name)
    }

    private fun saveActive() {
        val active = readActive() ?: return
        if (liveFile.isFile) writeAtomically(fileOf(active), liveFile.readBytes())
        rememberCharacter()
    }

    private fun liveBytes(): ByteArray = if (liveFile.isFile) liveFile.readBytes() else ByteArray(0)

    private fun names(): List<String> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXTENSION) && !it.name.startsWith(".") }
            ?.map { it.name.removeSuffix(EXTENSION) }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()

    private fun freeName(base: String): String {
        if (!fileOf(base).exists()) return base
        var i = 2
        while (fileOf("$base $i").exists()) i++
        return "$base $i"
    }

    private fun readActive(): String? =
        activeFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun writeActive(name: String) = writeAtomically(activeFile, name.toByteArray())

    /** Write through a temporary file, so a crash mid-write never leaves a half-written file. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("Cannot write ${file.name}")
            }
        }
    }
}
