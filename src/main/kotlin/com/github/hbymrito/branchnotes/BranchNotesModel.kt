package com.github.hbymrito.branchnotes

import git4idea.repo.GitRepository

enum class BranchRefType {
    LOCAL,
    REMOTE,
}

data class BranchNoteEntry(
    val repository: GitRepository,
    val branchName: String,
    val refType: BranchRefType,
    val note: String?,
    val isCurrent: Boolean,
)

data class BranchNotesSnapshot(
    val entries: List<BranchNoteEntry>,
) {
    private val entriesByRef =
        entries.associateBy { BranchRefKey(it.repository.root.path, it.refType, it.branchName) }
    private val entriesByRefName =
        entries.groupBy { BranchRefName(it.refType, it.branchName) }

    val current: BranchNoteEntry? = entries.firstOrNull { it.isCurrent }
    val localEntries: List<BranchNoteEntry> = entries.filter { it.refType == BranchRefType.LOCAL }

    fun noteFor(branchName: String, refType: BranchRefType, repositoryPath: String?): String? {
        if (repositoryPath != null) {
            return entriesByRef[BranchRefKey(repositoryPath, refType, branchName)]?.note
        }
        return entriesByRefName[BranchRefName(refType, branchName)]
            ?.mapNotNull(BranchNoteEntry::note)
            ?.distinct()
            ?.singleOrNull()
    }

    internal fun newLocalEntriesSince(
        previous: BranchNotesSnapshot,
        previouslyKnownRepositoryPaths: Set<String>,
    ): List<BranchNoteEntry> {
        val previousRefs =
            previous.localEntries
                .mapTo(mutableSetOf()) {
                    BranchRefKey(it.repository.root.path, it.refType, it.branchName)
                }
        return localEntries.filter { entry ->
            entry.repository.root.path in previouslyKnownRepositoryPaths &&
                BranchRefKey(
                    entry.repository.root.path,
                    entry.refType,
                    entry.branchName,
                ) !in previousRefs
        }
    }

    internal fun newRemoteEntriesSince(
        previous: BranchNotesSnapshot,
        previouslyKnownRepositoryPaths: Set<String>,
    ): List<BranchNoteEntry> {
        val previousRefs =
            previous.entries
                .asSequence()
                .filter { it.refType == BranchRefType.REMOTE }
                .mapTo(mutableSetOf()) {
                    BranchRefKey(it.repository.root.path, it.refType, it.branchName)
                }
        return entries.filter { entry ->
            entry.refType == BranchRefType.REMOTE &&
                entry.repository.root.path in previouslyKnownRepositoryPaths &&
                BranchRefKey(
                    entry.repository.root.path,
                    entry.refType,
                    entry.branchName,
                ) !in previousRefs
        }
    }

    internal fun remoteNoteToInherit(
        localEntry: BranchNoteEntry,
        trackedRemoteBranchName: String,
    ): String? {
        if (localEntry.refType != BranchRefType.LOCAL || localEntry.note != null) return null
        return noteFor(
            trackedRemoteBranchName,
            BranchRefType.REMOTE,
            localEntry.repository.root.path,
        )
    }

    companion object {
        val EMPTY = BranchNotesSnapshot(emptyList())
    }

    private data class BranchRefKey(
        val repositoryPath: String,
        val refType: BranchRefType,
        val branchName: String,
    )

    private data class BranchRefName(
        val refType: BranchRefType,
        val branchName: String,
    )
}
