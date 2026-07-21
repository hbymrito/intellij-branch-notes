package com.github.hbymrito.branchnotes

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.tree.TreeUtil
import git4idea.repo.GitRepository
import java.awt.Component
import java.awt.Container
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

class BranchNotesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<GitLogBranchNotesDecorator>()
    }
}

@Service(Service.Level.PROJECT)
class GitLogBranchNotesDecorator(private val project: Project) : Disposable {
    private val service = BranchNotesService.getInstance(project)
    private val decoratedTrees = Collections.newSetFromMap(WeakHashMap<JTree, Boolean>())
    private val scannedRoots = Collections.newSetFromMap(WeakHashMap<Component, Boolean>())
    private var observedContentManager: ContentManager? = null

    private val contentManagerListener =
        object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                scheduleDecoration(event.content.component)
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                scheduleDecoration(event.content.component)
            }
        }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            BranchNotesListener.TOPIC,
            BranchNotesListener { decoratedTrees.toList().forEach(JTree::repaint) },
        )
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowRegistered(id: String) {
                    if (id == ToolWindowId.VCS) scheduleToolWindowBinding()
                }

                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == ToolWindowId.VCS) bindToolWindow(toolWindow, true)
                }
            },
        )

        scheduleToolWindowBinding()
    }

    override fun dispose() {
        observedContentManager?.removeContentManagerListener(contentManagerListener)
        observedContentManager = null
        decoratedTrees.clear()
        scannedRoots.clear()
    }

    private fun scheduleToolWindowBinding() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ToolWindowManager.getInstance(project)
                .getToolWindow(ToolWindowId.VCS)
                ?.let { bindToolWindow(it, false) }
        }
    }

    private fun bindToolWindow(toolWindow: ToolWindow, ensureContentManager: Boolean) {
        val contentManager =
            (if (ensureContentManager) toolWindow.contentManager else toolWindow.contentManagerIfCreated)
                ?: return
        if (observedContentManager !== contentManager) {
            observedContentManager?.removeContentManagerListener(contentManagerListener)
            contentManager.addContentManagerListener(contentManagerListener)
            observedContentManager = contentManager
        }
        contentManager.contents.forEach { scheduleDecoration(it.component) }
    }

    private fun scheduleDecoration(root: Component) {
        if (!scannedRoots.add(root)) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) decorateTrees(root)
        }
    }

    private fun decorateTrees(component: Component) {
        if (component is JTree) decorateTree(component)
        if (component is Container) component.components.forEach(::decorateTrees)
    }

    private fun decorateTree(tree: JTree) {
        val delegate = tree.cellRenderer ?: return
        if (delegate is NativeBranchNotesTreeCellRenderer) return
        if (!isNativeRenderer(delegate)) return

        tree.cellRenderer =
            NativeBranchNotesTreeCellRenderer(delegate) { branchName, repositoryPath ->
                service.snapshot.noteFor(branchName, repositoryPath)
            }
        decoratedTrees.add(tree)
        tree.repaint()
    }

    private fun isNativeRenderer(renderer: TreeCellRenderer): Boolean {
        var type: Class<*>? = renderer.javaClass
        while (type != null) {
            if (type.name == DASHBOARD_RENDERER_CLASS) return true
            type = type.superclass
        }
        return false
    }

    companion object {
        private const val DASHBOARD_RENDERER_CLASS =
            "git4idea.ui.branch.dashboard.BranchesTreeComponent\$BranchTreeCellRenderer"
    }
}

internal class NativeBranchNotesTreeCellRenderer(
    private val delegate: TreeCellRenderer,
    private val noteProvider: (branchName: String, repositoryPath: String?) -> String?,
) : TreeCellRenderer {
    private val localBranchCache = WeakHashMap<Any, LocalBranchRef>()

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component =
            delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val note = findNote(value) ?: return component
        findMainTextComponent(component)?.append(" [$note]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        return component
    }

    private fun findNote(value: Any?): String? {
        val userObject = TreeUtil.getUserObject(value)
        val branch =
            localBranchCache[userObject]
                ?: extractLocalBranch(userObject)?.also { localBranchCache[userObject] = it }
                ?: return null
        return noteProvider(branch.name, branch.repositoryPath)
    }

    private fun extractLocalBranch(userObject: Any?): LocalBranchRef? {
        if (userObject == null) return null
        return extractDashboardBranch(userObject)
    }

    private fun extractDashboardBranch(userObject: Any): LocalBranchRef? {
        if (!hasTypeInHierarchy(userObject.javaClass, DASHBOARD_REF_DESCRIPTOR_CLASS)) return null

        return runCatching {
                val refInfo = userObject.javaClass.getMethod("getRefInfo").invoke(userObject)
                val isLocal = refInfo.javaClass.getMethod("isLocalBranch").invoke(refInfo) as? Boolean
                if (isLocal != true) return null

                val branchName =
                    refInfo.javaClass.getMethod("getBranchName").invoke(refInfo) as? String
                        ?: return null
                val repositories =
                    refInfo.javaClass.getMethod("getRepositories").invoke(refInfo) as? List<*>
                val repositoryPath =
                    (repositories?.singleOrNull() as? GitRepository)?.root?.path
                LocalBranchRef(branchName, repositoryPath)
            }
            .getOrNull()
    }

    private fun hasTypeInHierarchy(initialType: Class<*>, expectedName: String): Boolean {
        var type: Class<*>? = initialType
        while (type != null) {
            if (type.name == expectedName) return true
            type = type.superclass
        }
        return false
    }

    private fun findMainTextComponent(component: Component): SimpleColoredComponent? {
        if (component is SimpleColoredComponent) return component
        if (component !is Container) return null
        return component.components.firstNotNullOfOrNull(::findMainTextComponent)
    }

    private data class LocalBranchRef(val name: String, val repositoryPath: String?)

    companion object {
        private const val DASHBOARD_REF_DESCRIPTOR_CLASS =
            "git4idea.ui.branch.dashboard.BranchNodeDescriptor\$Ref"
    }
}
