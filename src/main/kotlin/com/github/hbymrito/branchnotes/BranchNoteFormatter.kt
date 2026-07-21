package com.github.hbymrito.branchnotes

object BranchNoteFormatter {
    private const val CONFIG_PREFIX = "branch."
    private const val CONFIG_SUFFIX = ".description"

    fun display(branchName: String, note: String?): String {
        val compactNote = compact(note)
        return if (compactNote == null) branchName else "$branchName [$compactNote]"
    }

    fun compact(note: String?): String? =
        note
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }

    fun configKey(branchName: String): String = "$CONFIG_PREFIX$branchName$CONFIG_SUFFIX"

    fun branchNameFromConfigKey(key: String): String? {
        if (!key.startsWith(CONFIG_PREFIX) || !key.endsWith(CONFIG_SUFFIX)) return null
        return key.removePrefix(CONFIG_PREFIX).removeSuffix(CONFIG_SUFFIX).takeIf { it.isNotEmpty() }
    }
}
