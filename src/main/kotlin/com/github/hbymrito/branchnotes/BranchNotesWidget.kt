package com.github.hbymrito.branchnotes

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

class BranchNotesWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Git Branch Notes"

    override fun isAvailable(project: Project): Boolean = true

    override fun isEnabledByDefault(): Boolean = false

    override fun createWidget(project: Project): StatusBarWidget {
        project.service<GitLogBranchNotesDecorator>()
        return BranchNotesWidget(project)
    }

    companion object {
        const val WIDGET_ID = "GitBranchNotes"
    }
}

private class BranchNotesWidget(private val project: Project) : CustomStatusBarWidget, Disposable {
    private val service = BranchNotesService.getInstance(project)
    private val connection = project.messageBus.connect(this)
    private val label =
        JBLabel().apply {
            border = JBUI.Borders.empty(0, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (event.button == MouseEvent.BUTTON1) {
                            BranchNotesPopup.show(project, this@apply)
                        }
                    }
                }
            )
        }

    private var statusBar: StatusBar? = null

    init {
        connection.subscribe(BranchNotesListener.TOPIC, BranchNotesListener(::updateLabel))
        updateLabel()
    }

    override fun ID(): String = BranchNotesWidgetFactory.WIDGET_ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    private fun updateLabel() {
        val current = service.snapshot.current
        label.isVisible = current != null
        label.text = current?.let { BranchNoteFormatter.display(it.branchName, it.note) }.orEmpty()
        label.toolTipText =
            current?.let {
                "Git branch in ${it.repository.root.name}. Click to view branches and edit notes."
            }
    }
}
