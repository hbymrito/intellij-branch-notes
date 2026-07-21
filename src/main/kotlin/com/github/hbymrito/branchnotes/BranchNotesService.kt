package com.github.hbymrito.branchnotes

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.commands.Git
import git4idea.config.GitConfigUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class BranchNotesService(private val project: Project) {
    @Volatile private var currentSnapshot = BranchNotesSnapshot.EMPTY
    private val loading = AtomicBoolean(false)
    private val reloadRequested = AtomicBoolean(false)

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
            val loaded = runCatching(::loadSnapshot).onFailure { LOG.warn("Unable to load Git branch notes", it) }

            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    loaded.getOrNull()?.let { currentSnapshot = it }
                    project.messageBus.syncPublisher(BranchNotesListener.TOPIC).notesChanged()
                }
                loading.set(false)
                if (reloadRequested.getAndSet(false)) reloadAsync()
            }
        }
    }

    fun setNote(entry: BranchNoteEntry, note: String) {
        val compactNote = BranchNoteFormatter.compact(note)
        if (compactNote == null) {
            clearNote(entry)
            return
        }

        updateNote("Unable to save branch note") {
            GitConfigUtil.setValue(
                project,
                entry.repository.root,
                BranchNoteFormatter.configKey(entry.branchName),
                compactNote,
            )
        }
    }

    fun clearNote(entry: BranchNoteEntry) {
        updateNote("Unable to clear branch note") {
            Git.getInstance()
                .config(entry.repository, "--unset-all", BranchNoteFormatter.configKey(entry.branchName))
        }
    }

    private fun updateNote(errorTitle: String, update: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(update)
                .onSuccess { reloadAsync() }
                .onFailure { error ->
                    LOG.warn(errorTitle, error)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            Messages.showErrorDialog(project, error.message ?: error.toString(), errorTitle)
                        }
                    }
                }
        }
    }

    private fun loadSnapshot(): BranchNotesSnapshot {
        val entries =
            GitRepositoryManager.getInstance(project)
                .repositories
                .flatMap(::loadRepositoryEntries)
                .sortedWith(
                    compareBy<BranchNoteEntry> { it.repository.root.path }
                        .thenByDescending { it.isCurrent }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.branchName }
                )
        return BranchNotesSnapshot(entries)
    }

    private fun loadRepositoryEntries(repository: GitRepository): List<BranchNoteEntry> {
        val notes = loadDescriptions(repository)
        val currentBranchName = repository.currentBranch?.name
        return repository.branches.localBranches.map { branch ->
            BranchNoteEntry(
                repository = repository,
                branchName = branch.name,
                note = notes[branch.name],
                isCurrent = branch.name == currentBranchName,
            )
        }
    }

    private fun loadDescriptions(repository: GitRepository): Map<String, String> =
        runCatching {
                GitConfigUtil.getValues(project, repository.root, DESCRIPTION_KEY_PATTERN)
                    .mapNotNull { (key, values) ->
                        val branchName = BranchNoteFormatter.branchNameFromConfigKey(key)
                        val note = BranchNoteFormatter.compact(values.lastOrNull())
                        if (branchName != null && note != null) branchName to note else null
                    }
                    .toMap()
            }
            .onFailure { LOG.debug("Unable to read branch descriptions from ${repository.root.path}", it) }
            .getOrDefault(emptyMap())

    companion object {
        private val LOG = Logger.getInstance(BranchNotesService::class.java)
        private const val DESCRIPTION_KEY_PATTERN = "^branch\\..*\\.description$"

        fun getInstance(project: Project): BranchNotesService = project.service()
    }
}
