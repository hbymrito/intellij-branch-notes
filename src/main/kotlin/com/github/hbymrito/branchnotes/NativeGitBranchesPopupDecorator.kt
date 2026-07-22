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
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.tree.TreeUtil
import git4idea.repo.GitRepository
import java.awt.Component
import java.awt.Container
import java.beans.PropertyChangeListener
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
    private var observedContentManager: ContentManager? = null
    private var observedContent: Content? = null
    private var pendingDecorationRoot: Component? = null
    private var disposed = false

    private val contentPropertyChangeListener =
        PropertyChangeListener { event ->
            if (event.propertyName != Content.PROP_COMPONENT) return@PropertyChangeListener
            (event.oldValue as? Component)?.let(::cancelDecoration)
            (event.newValue as? Component)?.let(::scheduleDecoration)
        }

    private val contentManagerListener =
        object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                if (observedContentManager?.isSelected(event.content) == true) {
                    observeSelectedContent()
                }
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                observeSelectedContent()
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                cancelDecoration(event.content.component)
                if (observedContent === event.content) setObservedContent(null)
                observeSelectedContent()
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
                override fun toolWindowsRegistered(
                    ids: List<String>,
                    toolWindowManager: ToolWindowManager,
                ) {
                    if (ToolWindowId.VCS in ids) scheduleToolWindowBinding(toolWindowManager)
                }

                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == ToolWindowId.VCS) bindToolWindow(toolWindow, true)
                }
            },
        )

        scheduleToolWindowBinding()
    }

    override fun dispose() {
        disposed = true
        setObservedContent(null)
        observedContentManager?.removeContentManagerListener(contentManagerListener)
        observedContentManager = null
        pendingDecorationRoot = null
        decoratedTrees.clear()
    }

    private fun scheduleToolWindowBinding(
        toolWindowManager: ToolWindowManager? = null,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            (toolWindowManager ?: ToolWindowManager.getInstance(project))
                .getToolWindow(ToolWindowId.VCS)
                ?.let { bindToolWindow(it, false) }
        }
    }

    private fun bindToolWindow(toolWindow: ToolWindow, ensureContentManager: Boolean) {
        if (disposed) return
        val shouldEnsureContentManager = ensureContentManager || toolWindow.isVisible
        val contentManager =
            (if (shouldEnsureContentManager) toolWindow.contentManager else toolWindow.contentManagerIfCreated)
                ?: return
        if (observedContentManager !== contentManager) {
            setObservedContent(null)
            observedContentManager?.removeContentManagerListener(contentManagerListener)
            pendingDecorationRoot = null
            contentManager.addContentManagerListener(contentManagerListener)
            observedContentManager = contentManager
        }
        observeSelectedContent()
    }

    private fun observeSelectedContent() {
        setObservedContent(observedContentManager?.selectedContent)
    }

    private fun setObservedContent(content: Content?) {
        if (observedContent === content) {
            content?.component?.let(::scheduleDecoration)
            return
        }

        observedContent?.let { previous ->
            previous.removePropertyChangeListener(contentPropertyChangeListener)
            cancelDecoration(previous.component)
        }
        observedContent = content
        content?.let { selected ->
            selected.addPropertyChangeListener(contentPropertyChangeListener)
            scheduleDecoration(selected.component)
        }
    }

    private fun scheduleDecoration(root: Component) {
        if (disposed) return
        if (pendingDecorationRoot === root) return
        pendingDecorationRoot = root
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed || pendingDecorationRoot !== root) return@invokeLater
            pendingDecorationRoot = null
            decorateTrees(root)
        }
    }

    private fun cancelDecoration(root: Component) {
        if (pendingDecorationRoot === root) pendingDecorationRoot = null
    }

    private fun decorateTrees(component: Component): Boolean {
        if (component is JTree) return decorateTree(component)
        if (component !is Container) return false
        return component.components.any(::decorateTrees)
    }

    private fun decorateTree(tree: JTree): Boolean {
        val delegate = tree.cellRenderer ?: return false
        if (delegate is NativeBranchNotesTreeCellRenderer) {
            decoratedTrees.add(tree)
            return true
        }
        if (!isNativeRenderer(delegate)) return false

        tree.cellRenderer =
            NativeBranchNotesTreeCellRenderer(delegate) { branchName, repositoryPath ->
                service.snapshot.noteFor(branchName, repositoryPath)
            }
        decoratedTrees.add(tree)
        tree.repaint()
        return true
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
