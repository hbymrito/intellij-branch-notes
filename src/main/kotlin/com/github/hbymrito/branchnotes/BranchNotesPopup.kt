package com.github.hbymrito.branchnotes

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import git4idea.branch.GitBrancher
import javax.swing.Icon
import javax.swing.JComponent

object BranchNotesPopup {
    fun show(project: Project, anchor: JComponent) {
        val service = BranchNotesService.getInstance(project)
        val entries = service.snapshot.entries
        if (entries.isEmpty()) {
            service.reloadAsync()
            return
        }

        JBPopupFactory.getInstance()
            .createListPopup(BranchListStep(project, service, entries))
            .showUnderneathOf(anchor)
    }
}

private class BranchListStep(
    private val project: Project,
    private val service: BranchNotesService,
    entries: List<BranchNoteEntry>,
) : BaseListPopupStep<BranchNoteEntry>("Git Branch Notes", entries) {
    private val multipleRepositories = entries.map { it.repository.root.path }.distinct().size > 1

    init {
        setDefaultOptionIndex(entries.indexOfFirst { it.isCurrent }.coerceAtLeast(0))
    }

    override fun getTextFor(value: BranchNoteEntry): String =
        BranchNoteFormatter.display(value.branchName, value.note)

    override fun getIconFor(value: BranchNoteEntry): Icon =
        if (value.isCurrent) AllIcons.Actions.Checked else AllIcons.Vcs.Branch

    override fun hasSubstep(selectedValue: BranchNoteEntry): Boolean = true

    override fun onChosen(selectedValue: BranchNoteEntry, finalChoice: Boolean): PopupStep<*> =
        BranchActionsStep(project, service, selectedValue)

    override fun getSeparatorAbove(value: BranchNoteEntry): ListSeparator? {
        if (!multipleRepositories) return null
        val firstForRepository = values.firstOrNull { it.repository.root.path == value.repository.root.path }
        return if (firstForRepository == value) ListSeparator(value.repository.root.name) else null
    }
}

private class BranchActionsStep(
    private val project: Project,
    private val service: BranchNotesService,
    private val entry: BranchNoteEntry,
) : BaseListPopupStep<BranchAction>(
        BranchNoteFormatter.display(entry.branchName, entry.note),
        buildList {
            if (!entry.isCurrent) add(BranchAction.CHECKOUT)
            add(BranchAction.EDIT_NOTE)
            if (entry.note != null) add(BranchAction.CLEAR_NOTE)
        },
    ) {

    override fun getTextFor(value: BranchAction): String = value.text

    override fun getIconFor(value: BranchAction): Icon = value.icon

    override fun onChosen(selectedValue: BranchAction, finalChoice: Boolean): PopupStep<*>? =
        doFinalStep {
            when (selectedValue) {
                BranchAction.CHECKOUT ->
                    GitBrancher.getInstance(project)
                        .checkout(
                            entry.branchName,
                            false,
                            listOf(entry.repository),
                            Runnable(service::reloadAsync),
                        )
                BranchAction.EDIT_NOTE -> editNote()
                BranchAction.CLEAR_NOTE -> service.clearNote(entry)
            }
        }

    private fun editNote() {
        val note =
            Messages.showInputDialog(
                project,
                "Remark for ${entry.branchName}",
                "Edit Git Branch Remark",
                AllIcons.Actions.Edit,
                entry.note.orEmpty(),
                null,
            ) ?: return
        service.setNote(entry, note)
    }
}

private enum class BranchAction(val text: String, val icon: Icon) {
    CHECKOUT("Checkout", AllIcons.Vcs.Branch),
    EDIT_NOTE("Edit Remark...", AllIcons.Actions.Edit),
    CLEAR_NOTE("Clear Remark", AllIcons.Actions.GC),
}
