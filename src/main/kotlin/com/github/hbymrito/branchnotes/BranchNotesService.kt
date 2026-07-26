package com.github.hbymrito.branchnotes

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.commands.Git
import git4idea.config.GitConfigUtil
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class BranchNotesService(private val project: Project) {
    @Volatile private var currentSnapshot = BranchNotesSnapshot.EMPTY
    @Volatile private var snapshotInitialized = false
    @Volatile private var knownRepositoryPaths = emptySet<String>()
    private val loading = AtomicBoolean(false)
    private val reloadRequested = AtomicBoolean(false)
    private val pendingInheritances = mutableMapOf<LocalBranchKey, Int>()
    private val pendingRemoteInheritances = mutableMapOf<RemoteBranchKey, Int>()

    val snapshot: BranchNotesSnapshot
        get() = currentSnapshot

    init {
        reloadAsync()
        ApplicationManager.getApplication().invokeLater { reloadAsync() }
    }

    fun reloadAsync() {
        if (project.isDisposed) return
        if (!loading.compareAndSet(false, true)) {
            reloadRequested.set(true)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded =
                runCatching {
                        val result = loadSnapshot()
                        if (!snapshotInitialized) {
                            result
                        } else {
                            val withInheritedLocalNotes =
                                inheritNotesForNewLocalBranches(
                                    currentSnapshot,
                                    result.snapshot,
                                    knownRepositoryPaths,
                                )
                            result.copy(
                                snapshot =
                                    inheritNotesForNewRemoteBranches(
                                        currentSnapshot,
                                        withInheritedLocalNotes,
                                        knownRepositoryPaths,
                                    )
                            )
                        }
                    }
                    .onFailure { LOG.warn("Unable to load Git branch notes", it) }

            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    loaded.getOrNull()?.let {
                        currentSnapshot = it.snapshot
                        knownRepositoryPaths = it.repositoryPaths
                        snapshotInitialized = true
                    }
                    project.messageBus.syncPublisher(BranchNotesListener.TOPIC).notesChanged()
                }
                loading.set(false)
                if (reloadRequested.getAndSet(false)) reloadAsync()
            }
        }
    }

    fun setNote(entry: BranchNoteEntry, note: String) {
        setNote(listOf(entry), note)
    }

    fun setNote(entries: List<BranchNoteEntry>, note: String) {
        val compactNote = BranchNoteFormatter.compact(note)
        if (compactNote == null) {
            clearNote(entries)
            return
        }

        updateNotes("Unable to save branch note", entries) { entry ->
            GitConfigUtil.setValue(
                project,
                entry.repository.root,
                BranchNoteFormatter.configKey(entry.branchName, entry.refType),
                compactNote,
            )
        }
    }

    fun clearNote(entry: BranchNoteEntry) {
        clearNote(listOf(entry))
    }

    fun clearNote(entries: List<BranchNoteEntry>) {
        updateNotes("Unable to clear branch note", entries) { entry ->
            Git.getInstance()
                .config(
                    entry.repository,
                    "--unset-all",
                    BranchNoteFormatter.configKey(entry.branchName, entry.refType),
                )
        }
    }

    fun entriesFor(
        repositories: List<GitRepository>,
        branchName: String,
        refType: BranchRefType,
    ): List<BranchNoteEntry> =
        repositories.map { repository ->
            BranchNoteEntry(
                repository = repository,
                branchName = branchName,
                refType = refType,
                note = snapshot.noteFor(branchName, refType, repository.root.path),
                isCurrent =
                    refType == BranchRefType.LOCAL &&
                        repository.currentBranch?.name == branchName,
            )
        }

    internal fun inheritLocalNoteAfterPush(
        repository: GitRepository,
        pushResult: GitPushRepoResult,
    ) {
        val target = pushResult.branchNoteTarget() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val inherited =
                runCatching {
                        val localNote =
                            GitConfigUtil.getValue(
                                project,
                                repository.root,
                                BranchNoteFormatter.configKey(
                                    target.localBranchName,
                                    BranchRefType.LOCAL,
                                ),
                            )
                        val remoteKey =
                            BranchNoteFormatter.configKey(
                                target.remoteBranchName,
                                BranchRefType.REMOTE,
                            )
                        val inheritedNote =
                            noteToInherit(
                                localNote,
                                GitConfigUtil.getValue(project, repository.root, remoteKey),
                            )
                        if (inheritedNote == null) {
                            false
                        } else {
                            GitConfigUtil.setValue(
                                project,
                                repository.root,
                                remoteKey,
                                inheritedNote,
                            )
                            true
                        }
                    }
                    .onFailure {
                        LOG.warn(
                            "Unable to inherit branch note from ${target.localBranchName} to ${target.remoteBranchName}",
                            it,
                        )
                    }
                    .getOrDefault(false)
            if (inherited) reloadAsync()
        }
    }

    private fun updateNotes(
        errorTitle: String,
        entries: List<BranchNoteEntry>,
        update: (BranchNoteEntry) -> Unit,
    ) {
        if (entries.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val errors =
                entries.mapNotNull { entry ->
                    runCatching { update(entry) }.exceptionOrNull()
                }
            reloadAsync()
            errors.firstOrNull()?.let { error ->
                LOG.warn(errorTitle, error)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        Messages.showErrorDialog(project, error.message ?: error.toString(), errorTitle)
                    }
                }
            }
        }
    }

    private fun loadSnapshot(): LoadedSnapshot {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val entries =
            repositories
                .flatMap(::loadRepositoryEntries)
                .sortedWith(
                    compareBy<BranchNoteEntry> { it.repository.root.path }
                        .thenByDescending { it.isCurrent }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.branchName }
                )
        return LoadedSnapshot(
            snapshot = BranchNotesSnapshot(entries),
            repositoryPaths = repositories.mapTo(mutableSetOf()) { it.root.path },
        )
    }

    private fun inheritNotesForNewLocalBranches(
        previous: BranchNotesSnapshot,
        loaded: BranchNotesSnapshot,
        previouslyKnownRepositoryPaths: Set<String>,
    ): BranchNotesSnapshot {
        loaded
            .newLocalEntriesSince(previous, previouslyKnownRepositoryPaths)
            .filter { it.note == null }
            .forEach { pendingInheritances.putIfAbsent(LocalBranchKey.from(it), 0) }

        val currentLocalEntries =
            loaded.localEntries.associateBy(LocalBranchKey::from)
        pendingInheritances.keys.retainAll(currentLocalEntries.keys)

        val inheritedNotes = mutableMapOf<LocalBranchKey, String>()
        var retryRequested = false
        pendingInheritances.keys.toList().forEach { key ->
            val entry = currentLocalEntries.getValue(key)
            if (entry.note != null) {
                pendingInheritances.remove(key)
                return@forEach
            }

            val trackInfo = entry.repository.getBranchTrackInfo(entry.branchName)
            if (trackInfo == null) {
                val attempts = pendingInheritances.getValue(key) + 1
                if (attempts < MAX_TRACKING_LOOKUP_ATTEMPTS) {
                    pendingInheritances[key] = attempts
                    retryRequested = true
                } else {
                    pendingInheritances.remove(key)
                }
                return@forEach
            }

            pendingInheritances.remove(key)
            val remoteNote =
                loaded.remoteNoteToInherit(
                    entry,
                    trackInfo.remoteBranch.name,
                ) ?: return@forEach
            runCatching {
                    GitConfigUtil.setValue(
                        project,
                        entry.repository.root,
                        BranchNoteFormatter.configKey(entry.branchName, BranchRefType.LOCAL),
                        remoteNote,
                    )
                }
                .onSuccess { inheritedNotes[key] = remoteNote }
                .onFailure {
                    LOG.warn(
                        "Unable to inherit branch note from ${trackInfo.remoteBranch.name} to ${entry.branchName}",
                        it,
                    )
                }
        }
        if (retryRequested) reloadRequested.set(true)
        if (inheritedNotes.isEmpty()) return loaded

        return BranchNotesSnapshot(
            loaded.entries.map { entry ->
                if (entry.refType == BranchRefType.LOCAL) {
                    inheritedNotes[LocalBranchKey.from(entry)]?.let { entry.copy(note = it) }
                        ?: entry
                } else {
                    entry
                }
            }
        )
    }

    private fun inheritNotesForNewRemoteBranches(
        previous: BranchNotesSnapshot,
        loaded: BranchNotesSnapshot,
        previouslyKnownRepositoryPaths: Set<String>,
    ): BranchNotesSnapshot {
        loaded
            .newRemoteEntriesSince(previous, previouslyKnownRepositoryPaths)
            .filter { it.note == null }
            .forEach { pendingRemoteInheritances.putIfAbsent(RemoteBranchKey.from(it), 0) }
        if (pendingRemoteInheritances.isEmpty()) return loaded

        val currentRemoteEntries =
            loaded.entries
                .filter { it.refType == BranchRefType.REMOTE }
                .associateBy(RemoteBranchKey::from)
        pendingRemoteInheritances.keys.retainAll(currentRemoteEntries.keys)
        if (pendingRemoteInheritances.isEmpty()) return loaded

        val pendingRepositoryPaths =
            pendingRemoteInheritances.keys.mapTo(mutableSetOf(), RemoteBranchKey::repositoryPath)
        val trackedLocalEntries =
            indexTrackedLocalEntriesByRemote(
                loaded.localEntries.filter { it.repository.root.path in pendingRepositoryPaths }
            ) { localEntry ->
                localEntry.repository
                    .getBranchTrackInfo(localEntry.branchName)
                    ?.remoteBranch
                    ?.name
            }

        val inheritedNotes = mutableMapOf<RemoteBranchKey, String>()
        var retryRequested = false
        pendingRemoteInheritances.keys.toList().forEach { key ->
            val remoteEntry = currentRemoteEntries.getValue(key)
            if (remoteEntry.note != null) {
                pendingRemoteInheritances.remove(key)
                return@forEach
            }

            val trackedLocalEntry = trackedLocalEntries[key]
            if (trackedLocalEntry == null) {
                val attempts = pendingRemoteInheritances.getValue(key) + 1
                if (attempts < MAX_TRACKING_LOOKUP_ATTEMPTS) {
                    pendingRemoteInheritances[key] = attempts
                    retryRequested = true
                } else {
                    pendingRemoteInheritances.remove(key)
                }
                return@forEach
            }

            pendingRemoteInheritances.remove(key)
            val remoteKey =
                BranchNoteFormatter.configKey(
                    remoteEntry.branchName,
                    BranchRefType.REMOTE,
                )
            runCatching {
                    val inheritedNote =
                        noteToInherit(
                            trackedLocalEntry.note,
                            GitConfigUtil.getValue(project, remoteEntry.repository.root, remoteKey),
                        ) ?: return@runCatching null
                    GitConfigUtil.setValue(
                        project,
                        remoteEntry.repository.root,
                        remoteKey,
                        inheritedNote,
                    )
                    inheritedNote
                }
                .onSuccess { inheritedNote ->
                    inheritedNote?.let { inheritedNotes[key] = it }
                }
                .onFailure {
                    LOG.warn(
                        "Unable to inherit branch note from ${trackedLocalEntry.branchName} to ${remoteEntry.branchName}",
                        it,
                    )
                }
        }
        if (retryRequested) reloadRequested.set(true)
        if (inheritedNotes.isEmpty()) return loaded

        return BranchNotesSnapshot(
            loaded.entries.map { entry ->
                if (entry.refType == BranchRefType.REMOTE) {
                    inheritedNotes[RemoteBranchKey.from(entry)]?.let { entry.copy(note = it) }
                        ?: entry
                } else {
                    entry
                }
            }
        )
    }

    private fun loadRepositoryEntries(repository: GitRepository): List<BranchNoteEntry> {
        val localNotes = loadDescriptions(repository, BranchRefType.LOCAL)
        val remoteNotes = loadDescriptions(repository, BranchRefType.REMOTE)
        val currentBranchName = repository.currentBranch?.name
        val localEntries =
            repository.branches.localBranches.map { branch ->
                BranchNoteEntry(
                    repository = repository,
                    branchName = branch.name,
                    refType = BranchRefType.LOCAL,
                    note = localNotes[branch.name],
                    isCurrent = branch.name == currentBranchName,
                )
            }
        val remoteEntries =
            repository.branches.remoteBranches.map { branch ->
                BranchNoteEntry(
                    repository = repository,
                    branchName = branch.name,
                    refType = BranchRefType.REMOTE,
                    note = remoteNotes[branch.name],
                    isCurrent = false,
                )
            }
        return localEntries + remoteEntries
    }

    private fun loadDescriptions(
        repository: GitRepository,
        refType: BranchRefType,
    ): Map<String, String> =
        runCatching {
                GitConfigUtil.getValues(project, repository.root, descriptionKeyPattern(refType))
                    .mapNotNull { (key, values) ->
                        val branchName = BranchNoteFormatter.branchNameFromConfigKey(key, refType)
                        val note = BranchNoteFormatter.compact(values.lastOrNull())
                        if (branchName != null && note != null) branchName to note else null
                    }
                    .toMap()
            }
            .onFailure { LOG.debug("Unable to read branch descriptions from ${repository.root.path}", it) }
            .getOrDefault(emptyMap())

    companion object {
        private val LOG = Logger.getInstance(BranchNotesService::class.java)
        private const val MAX_TRACKING_LOOKUP_ATTEMPTS = 2
        private const val LOCAL_DESCRIPTION_KEY_PATTERN = "^branch\\..*\\.description$"
        private const val REMOTE_DESCRIPTION_KEY_PATTERN =
            "^branchnotes\\.remote\\..*\\.description$"

        private fun descriptionKeyPattern(refType: BranchRefType): String =
            when (refType) {
                BranchRefType.LOCAL -> LOCAL_DESCRIPTION_KEY_PATTERN
                BranchRefType.REMOTE -> REMOTE_DESCRIPTION_KEY_PATTERN
            }

        fun getInstance(project: Project): BranchNotesService = project.service()
    }

    private data class LoadedSnapshot(
        val snapshot: BranchNotesSnapshot,
        val repositoryPaths: Set<String>,
    )

    private data class LocalBranchKey(
        val repositoryPath: String,
        val branchName: String,
    ) {
        companion object {
            fun from(entry: BranchNoteEntry): LocalBranchKey =
                LocalBranchKey(entry.repository.root.path, entry.branchName)
        }
    }

}

internal data class RemoteBranchKey(
    val repositoryPath: String,
    val branchName: String,
) {
    companion object {
        fun from(entry: BranchNoteEntry): RemoteBranchKey =
            RemoteBranchKey(entry.repository.root.path, entry.branchName)
    }
}

internal fun indexTrackedLocalEntriesByRemote(
    localEntries: List<BranchNoteEntry>,
    trackedRemoteBranchName: (BranchNoteEntry) -> String?,
): Map<RemoteBranchKey, BranchNoteEntry> {
    val entriesByRemote = mutableMapOf<RemoteBranchKey, BranchNoteEntry>()
    localEntries.forEach { localEntry ->
        val remoteBranchName = trackedRemoteBranchName(localEntry) ?: return@forEach
        entriesByRemote.putIfAbsent(
            RemoteBranchKey(localEntry.repository.root.path, remoteBranchName),
            localEntry,
        )
    }
    return entriesByRemote
}
