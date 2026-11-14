package org.openmw.phone

/**
 * Reading and changing values in the lines of settings.cfg, by section and key. Keys match without
 * regard to case, and commented-out lines are skipped.
 */
internal object SettingsLines {

    private fun keyOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || !trimmed.contains('=')) return null
        return trimmed.substringBefore('=').trim()
    }

    private fun isSection(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("[") && trimmed.endsWith("]")
    }

    private fun sectionStart(lines: List<String>, section: String) =
        lines.indexOfFirst { it.trim().equals(section, ignoreCase = true) }

    /** The value of [key] in [section], or null if it is not there. */
    fun get(lines: List<String>, section: String, key: String): String? {
        val start = sectionStart(lines, section)
        if (start < 0) return null
        for (i in start + 1 until lines.size) {
            if (isSection(lines[i])) break
            if (keyOf(lines[i]).equals(key, ignoreCase = true)) return lines[i].substringAfter('=').trim()
        }
        return null
    }

    /** Sets [key] in [section], adding the key, and the section at the end, where missing. */
    fun set(lines: MutableList<String>, section: String, key: String, value: String) {
        var start = sectionStart(lines, section)
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(section)
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
