package org.openmw.fragments

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openmw.Constants
import org.openmw.R
import org.openmw.ui.controls.UIStateManager.customCFG
import org.openmw.utils.IniConverter
import org.openmw.utils.MToast
import org.openmw.utils.dataStore
import org.openmw.utils.prefsData
import org.openmw.utils.stringRes
import java.io.File

private val job = Job()
private val scope = CoroutineScope(Dispatchers.IO + job)

/**
 * The header `UserManageAssets.onFirstLaunch` seeds an untouched user openmw.cfg with, and the marker
 * [updateUserConfig] tests for to decide the file holds nothing worth protecting.
 *
 * Hoisted out of that function so the two places that care — the pristine test here and the
 * simplified launcher, which restores this line when a folder removal empties the file — compare
 * against ONE literal. It was previously a local `val` here duplicated by a literal in
 * `ManageAssets`, where a one-character drift between copies would silently disable the reseed path.
 */
internal const val USER_CFG_DEFAULT_LINE =
    "# This is the user openmw.cfg. Feel free to modify it as you wish."

private data class GameFiles(
    val esmContentLines: List<String>,
    val bsaFallbackLines: List<String>
)

private fun findFilesWithExtensions(directory: DocumentFile?, extensions: Array<String>): List<DocumentFile> {
    return directory?.listFiles()?.filter { file ->
        extensions.any { file.name?.endsWith(".$it") == true }
    } ?: emptyList()
}

fun modifyFileLineByNumber(filePath: String, lineNumber: Int, newContent: String) {
    val lines = File(filePath).readLines().toMutableList()
    if (lineNumber in 1..lines.size) {
        lines[lineNumber - 1] = newContent
        File(filePath).writeText(lines.joinToString("\n"))
    } else {
        println("Line number out of range")
    }
}

/**
 * Searches for a line containing `searchString` and replaces it with `newContent`.
 * @param filePath Path to the file
 * @param searchString String to search for in the file
 * @param newContent New content to replace the matching line
 * @param replaceAll If true, replaces all matching lines; if false, replaces only the first match
 * @return true if replacement was made, false if no match was found
 */
fun modifyFileLine(
    filePath: String,
    searchString: String,
    newContent: String,
    replaceAll: Boolean = false
): Boolean {
    val file = File(filePath)
    val lines = file.readLines().toMutableList()
    var replacementMade = false

    for (i in lines.indices) {
        if (lines[i].contains(searchString)) {
            lines[i] = newContent
            replacementMade = true
            if (!replaceAll) break
        }
    }

    if (replacementMade) {
        file.writeText(lines.joinToString("\n"))
    }

    return replacementMade
}

fun modifyFileLineExactMatch(
    filePath: String,
    exactLineContent: String,
    newContent: String,
    replaceAll: Boolean = false
): Boolean {
    val file = File(filePath)
    val lines = file.readLines().toMutableList()
    var replacementMade = false

    for (i in lines.indices) {
        if (lines[i] == exactLineContent) {
            lines[i] = newContent
            replacementMade = true
            if (!replaceAll) break
        }
    }

    if (replacementMade) {
        file.writeText(lines.joinToString("\n"))
    }

    return replacementMade
}

fun onFirstLaunch(context: Context) {
    val dataStoreKey = stringPreferencesKey("game_files_uri")
    scope.launch {
        try {
            val uriString = context.prefsData.first()[dataStoreKey]
            if (!uriString.isNullOrBlank()) {
                val folder = File(uriString)
                val cfgFile = File(Constants.OPENMW_CFG)

                val needsUpdate = if (!cfgFile.exists()) {
                    true
                } else {
                    val content = cfgFile.readText()
                    !content.contains("vfs-mw") || !content.contains("data-local=") ||
                        !content.contains("Mods/companion")
                }

                if (needsUpdate) {
                    val selectedDirectory = DocumentFile.fromFile(folder)
                    val iniFile = selectedDirectory.findFile("Morrowind.ini")
                    val dataFilesFolder = selectedDirectory.findFile("Data Files")

                    if (iniFile != null && dataFilesFolder?.isDirectory == true) {
                        val gameFiles = scanForGameFiles(dataFilesFolder)
                        val iniData = context.contentResolver.openInputStream(iniFile.uri)?.use {
                            it.bufferedReader().readText()
                        } ?: ""
                        val convertedData = IniConverter(iniData).convert()

                        updateMainConfig(folder.absolutePath, gameFiles, convertedData)
                        //updateUserConfig(folder.absolutePath, gameFiles)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun processSelectedFolder(context: Context, folder: File, onUriPersisted: (String?) -> Unit) {
    val dataStoreKey = stringPreferencesKey("game_files_uri")
    scope.launch {
        try {
            val selectedDirectory = DocumentFile.fromFile(folder)
            val iniFile = selectedDirectory.findFile("Morrowind.ini")
            val dataFilesFolder = selectedDirectory.findFile("Data Files")

            if (iniFile != null && dataFilesFolder?.isDirectory == true) {
                // Persist URI and notify UI
                val uriString = context.prefsData.first()[dataStoreKey]
                onUriPersisted(uriString)

                val gameFiles = scanForGameFiles(dataFilesFolder)

                // 1. Process main openmw.cfg
                val iniData = context.contentResolver.openInputStream(iniFile.uri)?.use {
                    it.bufferedReader().readText()
                } ?: ""
                val convertedData = IniConverter(iniData).convert()

                updateMainConfig(folder.absolutePath, gameFiles, convertedData)
                updateUserConfig(folder.absolutePath, gameFiles)
            } else {
                context.dataStore.edit { it[dataStoreKey] = "" }
                onUriPersisted("")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onUriPersisted(null)
            MToast(stringRes(R.string.an_error_occurred_while_selecting_the_folder))
        }
    }
}

private fun scanForGameFiles(dataFilesFolder: DocumentFile): GameFiles {
    val extensions = arrayOf("esm", "bsa")
    val files = findFilesWithExtensions(dataFilesFolder, extensions)
    val existingFiles = files.mapNotNull { it.name }.toSet()

    val orderedEsmFiles = listOf("Morrowind.esm", "Tribunal.esm", "Bloodmoon.esm")
    val orderedBsaFiles = listOf("Morrowind.bsa", "Tribunal.bsa", "Bloodmoon.bsa")

    return GameFiles(
        esmContentLines = orderedEsmFiles.intersect(existingFiles).map { "content=$it" },
        bsaFallbackLines = orderedBsaFiles.intersect(existingFiles).map { "fallback-archive=$it" }
    )
}

/**
 * Is [line] the internal config's MORROWIND `data=` line — the one [updateMainConfig] repoints at
 * whichever game folder was just selected?
 *
 * **This exists because that line has to be matched by SHAPE, and both of the obvious shapes are
 * wrong** (Aug 2026). The internal `openmw.cfg` carries several `data=` lines, and only one of them
 * belongs to the player's Morrowind install:
 *
 * ```
 * data="<morrowind>/Data Files"                  <- this one, and only this one
 * data=<USER_RESOURCES>/vfs-mw                   <- ours (unquoted)
 * data="<USER_FILE_STORAGE>/OpenMW/Mods/companion"  <- ours (quoted, so a lazy regex hits it)
 * data-local=<USER_FILE_STORAGE>/OpenMW/Override <- ours
 * ```
 *
 * The two failed attempts, in order, because neither should be reintroduced:
 *
 *  - `^data\s*=\s*".*?"` (Alpha3's original) matches ANY quoted line, so it also rewrote the
 *    companion mod line into a second copy of the Morrowind path. That is the clobbering the
 *    duplicate-dropping pass just below still cleans up after.
 *  - `^data\s*=\s*"specify-me!"` (the over-correction that replaced it) matches only the
 *    placeholder `app/openmw.cfg` ships. It cured the clobbering by making the line **write-once**:
 *    the first folder selection after a fresh install consumes the sentinel, and every later
 *    selection then silently fails to update it. The internal config stays frozen on the first
 *    folder ever chosen while [updateUserConfig] follows the newest one, so the engine registers
 *    BOTH as VFS data directories. Measured cost of that on one device: a second full scan of a
 *    whole Data Files tree at startup (27.6s of data-directory indexing against 14.6s once fixed),
 *    plus every loose asset of the abandoned folder silently live in the VFS.
 *
 * So: the sentinel, OR a quoted path ending in `Data Files`, AND never one of our own paths. The
 * app-storage exclusion is belt-and-braces — no app-managed line ends in `Data Files` today — but it
 * is what keeps this correct if one ever does.
 *
 * Only the INTERNAL config is in scope. Mods added later append their `data=` lines to the USER
 * config (`ModAssistant` / the simplified launcher), which this function never touches.
 */
private fun isMorrowindDataLine(line: String): Boolean {
    val value = Regex("""^data\s*=\s*"(.*)"\s*$""").find(line.trim())?.groupValues?.get(1)
        ?: return false
    if (value == "specify-me!") return true
    if (value.startsWith(Constants.USER_FILE_STORAGE) || value.startsWith(Constants.USER_RESOURCES)) {
        return false
    }
    return value.trimEnd('/', '\\').endsWith("Data Files", ignoreCase = true)
}

/** The key of a `fallback=Key,value` line. */
private fun fallbackKey(line: String): String = line.removePrefix("fallback=").substringBefore(",")

/**
 * The game's main font, by the name the engine looks it up under (`FontLoader::loadFonts`).
 *
 * Morrowind.ini names its own bitmap font there, `magic_cards_regular`. A bitmap font keeps the
 * size baked into its .fnt file, so `[GUI] font size` does nothing to it: only the line height
 * follows the setting (`FontLoader::overrideLineHeight`). MysticCards is OpenMW's TrueType copy of
 * that font, shipped in `resources/vfs/fonts`, and it takes its size from the setting. Tried on the
 * Duo Lite on Nov 5 2026: with the bitmap font, `font size = 18` changed nothing on screen; with
 * MysticCards the tooltips on the top screen became readable.
 *
 * A player who wants the original font back puts `fallback=Fonts_Font_0,magic_cards_regular` in
 * the user openmw.cfg. The engine reads that file after this one, and for a repeated fallback key
 * the last value wins (`components/fallback/validate.cpp`).
 */
private const val GAME_FONT_KEY = "Fonts_Font_0"
private const val GAME_FONT_LINE = "fallback=$GAME_FONT_KEY,MysticCards"

private fun withGameFont(line: String): String =
    if (line.startsWith("fallback=") && fallbackKey(line) == GAME_FONT_KEY) GAME_FONT_LINE else line

/**
 * Point the main font at MysticCards in the internal openmw.cfg ([GAME_FONT_LINE]).
 *
 * Runs on every launcher start, before the engine is started. [updateMainConfig] does the same for
 * a new Morrowind.ini import, but it only runs on a folder selection, so installs that imported the
 * ini before this change would otherwise keep the bitmap font. Only an existing line is changed:
 * without one the ini has not been imported yet, and [updateMainConfig] writes it then.
 */
fun useTrueTypeGameFont() {
    val cfgFile = File(Constants.OPENMW_CFG)
    if (!cfgFile.exists()) return
    val original = cfgFile.readLines()
    val lines = original.map(::withGameFont)
    if (lines != original) cfgFile.writeText(lines.joinToString("\n") + "\n")
}

private fun updateMainConfig(savedPath: String, gameFiles: GameFiles, convertedData: String) {
    val cfgFile = File(Constants.OPENMW_CFG)
    val replacementStringData = """data="${savedPath}/Data Files""""
    val vfsPathLine = "data=${Constants.USER_RESOURCES}/vfs-mw"
    val overridePathLine = "data-local=${Constants.USER_FILE_STORAGE}/OpenMW/Override"
    val companionPathLine = """data="${Constants.USER_FILE_STORAGE}/OpenMW/Mods/companion""""

    val currentLines = if (cfgFile.exists()) cfgFile.readLines() else emptyList()

    val mappedLines = currentLines.map { line ->
        when {
            isMorrowindDataLine(line) -> replacementStringData
            line.contains("resources=./resources") -> line.replace(
                "resources=./resources",
                "resources=${Constants.USER_RESOURCES}"
            )
            else -> line
        }
    }

    // Drop duplicate Morrowind data lines. A stale `data="./Mods/companion"` line
    // clobbered by an earlier over-broad regex now reads identical to the real
    // data path; keep only the first occurrence so an existing config self-heals.
    var seenDataFiles = false
    val modifiedLines = mappedLines.filterNot { line ->
        if (line == replacementStringData) {
            val duplicate = seenDataFiles
            seenDataFiles = true
            duplicate
        } else {
            false
        }
    }

    // The Morrowind.ini values (`fallback=Key,value`) are merged by key. A key from the new ini
    // replaces the old line with that key, and old keys the new ini does not have are kept, so a
    // cut-short ini can never leave the engine without a value ("unknown fallback name"). The new
    // block goes where the first replaced line was. This used to be skipped whenever any line
    // contained "fallback", which `fallback-archive=` lines always do, so lines once lost never
    // came back.
    // The ini's font line is replaced on the way in, see GAME_FONT_LINE.
    val newFallbackLines = convertedData.lines().filter { it.startsWith("fallback=") }.map(::withGameFont)
    val newFallbackKeys = newFallbackLines.map { fallbackKey(it) }.toSet()
    fun isReplacedFallback(line: String) = line.startsWith("fallback=") && fallbackKey(line) in newFallbackKeys
    val firstReplaced = modifiedLines.indexOfFirst { isReplacedFallback(it) }
    val keptLines = modifiedLines.filterNot { isReplacedFallback(it) }.toMutableList()
    if (firstReplaced >= 0) keptLines.addAll(firstReplaced, newFallbackLines)

    val finalContent = buildString {
        keptLines.forEach { appendLine(it) }

        // Append game-specific settings if not already present
        gameFiles.bsaFallbackLines.forEach { line ->
            if (!modifiedLines.contains(line)) appendLine(line)
        }

        if (!modifiedLines.contains(vfsPathLine)) appendLine(vfsPathLine)

        // No old line had any of the new keys (first selection, or the lines were lost): the block
        // goes here, as it always has.
        if (firstReplaced < 0) newFallbackLines.forEach { appendLine(it) }

        if (!modifiedLines.contains(overridePathLine)) appendLine(overridePathLine)

        if (!modifiedLines.contains(companionPathLine)) appendLine(companionPathLine)
    }

    cfgFile.writeText(finalContent.trimEnd() + "\n")
}

private fun updateUserConfig(savedPath: String, gameFiles: GameFiles) {
    val esmFile = File(Constants.USER_OPENMW_CFG)
    val replacementStringData = """data="${savedPath}/Data Files""""

    // "Pristine" = there is nothing in the user cfg worth protecting, so a game-files selection may
    // (re)seed it. An EMPTY file counts, and that inclusion is a fix, not a nicety: the simplified
    // launcher's Manage-folders removal can legitimately empty this file, and until now re-selecting
    // game files afterwards wrote NOTHING — the guard only recognised a file holding exactly the
    // default header — so the player got a registered game folder with an empty load order and had
    // to re-add the Data Files folder by hand to recover.
    val pristine = !esmFile.exists() ||
        esmFile.readText().trim().let { it.isEmpty() || it == USER_CFG_DEFAULT_LINE }
    val shouldOverwrite = !customCFG || pristine

    if (shouldOverwrite) {
        esmFile.bufferedWriter().use { writer ->
            writer.write(replacementStringData)
            writer.newLine()
            gameFiles.esmContentLines.forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }
    } else {
        repointUserGameFolder(esmFile, replacementStringData, "$savedPath/Data Files")
    }
}

/**
 * Points the game folder line of a user cfg that has a load order at the folder just selected,
 * and leaves every other line as it is.
 *
 * Without this the user cfg kept the old folder while [updateMainConfig] moved the internal one
 * to the new folder, so the engine loaded both Data Files folders, and archives came from
 * whichever was registered last.
 *
 * A game folder line is an enabled `data=` line whose folder holds Morrowind.esm, or that already
 * names the new folder. A mod with its own "Data Files" folder has no Morrowind.esm, so it is
 * never touched. The first game folder line becomes the new folder and any later one is dropped.
 * A folder that is not on the device (an unmounted card) does not count and is left alone.
 */
private fun repointUserGameFolder(cfgFile: File, newDataLine: String, newFolder: String) {
    if (!cfgFile.exists()) return
    val lines = cfgFile.readLines()
    var replaced = false
    val result = lines.mapNotNull { line ->
        val folder = userDataFolder(line) ?: return@mapNotNull line
        val isGameFolder = sameFolder(folder, newFolder) || holdsMorrowindEsm(folder)
        when {
            !isGameFolder -> line
            replaced -> null
            else -> {
                replaced = true
                newDataLine
            }
        }
    }
    if (result != lines) cfgFile.writeText(result.joinToString("\n") + "\n")
}

/** The folder of an enabled `data=` line, or null for any other line. Quotes are removed. */
private fun userDataFolder(line: String): String? {
    val value = Regex("""^data\s*=\s*(.*)$""").find(line.trim())?.groupValues?.get(1)?.trim()
        ?: return null
    val unquoted = if (value.startsWith("\"")) value.drop(1).substringBefore("\"") else value
    return unquoted.ifEmpty { null }
}

private fun sameFolder(a: String, b: String) =
    a.trimEnd('/', '\\').equals(b.trimEnd('/', '\\'), ignoreCase = true)

private fun holdsMorrowindEsm(folder: String): Boolean =
    File(folder).listFiles()?.any { it.isFile && it.name.equals("Morrowind.esm", ignoreCase = true) } == true
