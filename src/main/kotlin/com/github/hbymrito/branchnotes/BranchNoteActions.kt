package com.github.hbymrito.branchnotes

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.GitBranch
import git4idea.actions.branch.GitSingleBranchAction
import git4idea.repo.GitRepository

class EditBranchNoteAction : GitSingleBranchAction() {
    init {
        templatePresentation.text = "Add Remark..."
        templatePresentation.icon = AllIcons.Actions.Edit
    }

    override fun updateIfEnabledAndVisible(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch,
    ) {
        val entries = entriesFor(project, repositories, reference)
        e.presentation.text =
            if (entries.any { it.note != null }) "Edit Remark..." else "Add Remark..."
    }

    override fun actionPerformed(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch,
    ) {
        val service = BranchNotesService.getInstance(project)
        val entries = entriesFor(project, repositories, reference)
        val existingNotes = entries.map(BranchNoteEntry::note).distinct()
        val multipleRepositories = entries.size > 1
        val differentNotes = existingNotes.size > 1
        val message =
            buildString {
                append("Remark for ${reference.name}")
                if (multipleRepositories) {
                    append("\nThis will update ${entries.size} repositories.")
                }
                if (differentNotes) {
                    append("\nThe selected repositories currently have different remarks.")
                }
            }
        val note =
            Messages.showInputDialog(
                project,
                message,
                "Edit Git Branch Remark",
                AllIcons.Actions.Edit,
                existingNotes.singleOrNull().orEmpty(),
                null,
            ) ?: return
        service.setNote(entries, note)
    }
}

class DeleteBranchNoteAction : GitSingleBranchAction() {
    init {
        templatePresentation.text = "Delete Remark"
        templatePresentation.icon = AllIcons.Actions.GC
    }

    override fun updateIfEnabledAndVisible(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch,
    ) {
        e.presentation.isEnabledAndVisible =
            entriesFor(project, repositories, reference).any { it.note != null }
    }

    override fun actionPerformed(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch,
    ) {
        val entries =
            entriesFor(project, repositories, reference).filter { it.note != null }
        if (entries.isEmpty()) return
        val repositoryText =
            if (entries.size == 1) "" else " from ${entries.size} repositories"
        if (
            Messages.showYesNoDialog(
                project,
                "Delete the remark for ${reference.name}$repositoryText?",
                "Delete Git Branch Remark",
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) {
            return
        }
        BranchNotesService.getInstance(project).clearNote(entries)
    }
}

private fun entriesFor(
    project: Project,
    repositories: List<GitRepository>,
    reference: GitBranch,
): List<BranchNoteEntry> =
    BranchNotesService.getInstance(project)
        .entriesFor(
            repositories,
            reference.name,
            if (reference.isRemote) BranchRefType.REMOTE else BranchRefType.LOCAL,
        )
