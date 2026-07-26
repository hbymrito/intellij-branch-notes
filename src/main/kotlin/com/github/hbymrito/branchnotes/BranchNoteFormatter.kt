package com.github.hbymrito.branchnotes

object BranchNoteFormatter {
    private const val LOCAL_CONFIG_PREFIX = "branch."
    private const val REMOTE_CONFIG_PREFIX = "branchnotes.remote."
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

    fun configKey(branchName: String, refType: BranchRefType = BranchRefType.LOCAL): String {
        val prefix =
            when (refType) {
                BranchRefType.LOCAL -> LOCAL_CONFIG_PREFIX
                BranchRefType.REMOTE -> REMOTE_CONFIG_PREFIX
            }
        return "$prefix$branchName$CONFIG_SUFFIX"
    }

    fun branchNameFromConfigKey(
        key: String,
        refType: BranchRefType = BranchRefType.LOCAL,
    ): String? {
        val prefix =
            when (refType) {
                BranchRefType.LOCAL -> LOCAL_CONFIG_PREFIX
                BranchRefType.REMOTE -> REMOTE_CONFIG_PREFIX
            }
        if (!key.startsWith(prefix) || !key.endsWith(CONFIG_SUFFIX)) return null
        return key.removePrefix(prefix).removeSuffix(CONFIG_SUFFIX).takeIf { it.isNotEmpty() }
    }
}
