package com.github.hbymrito.branchnotes

import git4idea.repo.GitRepository

data class BranchNoteEntry(
    val repository: GitRepository,
    val branchName: String,
    val note: String?,
    val isCurrent: Boolean,
)

data class BranchNotesSnapshot(
    val entries: List<BranchNoteEntry>,
) {
    private val entriesByBranchName = entries.groupBy(BranchNoteEntry::branchName)

    val current: BranchNoteEntry? = entries.firstOrNull { it.isCurrent }

    fun noteFor(branchName: String, repositoryPath: String?): String? {
        val candidates = entriesByBranchName[branchName] ?: return null
        return if (repositoryPath == null) {
            candidates.firstNotNullOfOrNull(BranchNoteEntry::note)
        } else {
            candidates.firstOrNull { it.repository.root.path == repositoryPath }?.note
        }
    }

    companion object {
        val EMPTY = BranchNotesSnapshot(emptyList())
    }
}
