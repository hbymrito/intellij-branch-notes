package com.github.hbymrito.branchnotes

import com.intellij.ui.SimpleColoredComponent
import git4idea.GitLocalBranch
import java.awt.Component
import java.awt.event.AWTEventListener
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativeBranchNotesTreeCellRendererTest {
    @Test
    fun `decorator never joins the global AWT dispatch path`() {
        assertFalse(
            AWTEventListener::class.java.isAssignableFrom(
                GitLogBranchNotesDecorator::class.java,
            ),
        )
    }

    @Test
    fun `does not annotate non-branch rows`() {
        val text = SimpleColoredComponent().apply { append("本地") }
        val renderer = NativeBranchNotesTreeCellRenderer(panelRenderer(text)) { _, _ -> "不应显示" }

        renderer.getTreeCellRendererComponent(
            JTree(),
            "LOCAL_BRANCHES_GROUP",
            false,
            true,
            false,
            0,
            false,
        )

        assertEquals("本地", text.getCharSequence(false).toString())
    }

    @Test
    fun `appends note to Git Log dashboard local branch row`() {
        val text = SimpleColoredComponent().apply { append("user-profile") }
        val renderer =
            NativeBranchNotesTreeCellRenderer(panelRenderer(text)) { branchName, repositoryPath ->
                assertEquals("feature/user-profile", branchName)
                assertEquals(null, repositoryPath)
                "Add user profile page"
            }

        renderer.getTreeCellRendererComponent(
            JTree(),
            createDashboardBranchNode("feature/user-profile", "user-profile"),
            false,
            false,
            true,
            0,
            false,
        )

        assertEquals("user-profile [Add user profile page]", text.getCharSequence(false).toString())
    }

    private fun panelRenderer(text: SimpleColoredComponent): TreeCellRenderer =
        TreeCellRenderer { _: JTree, _: Any?, _: Boolean, _: Boolean, _: Boolean, _: Int, _: Boolean ->
            JPanel().apply { add(text) } as Component
        }

    private fun createDashboardBranchNode(branchName: String, displayName: String): Any {
        val branchInfoClass = Class.forName("git4idea.ui.branch.dashboard.BranchInfo")
        val branchInfoConstructor = branchInfoClass.constructors.single { it.parameterCount == 5 }
        val countersType = branchInfoConstructor.parameterTypes[3]
        val emptyCounters = countersType.getField("EMPTY").get(null)
        val branchInfo =
            branchInfoConstructor
                .newInstance(
                    GitLocalBranch(branchName),
                    false,
                    false,
                    emptyCounters,
                    emptyList<Any>(),
                )

        val descriptorClass =
            Class.forName("git4idea.ui.branch.dashboard.BranchNodeDescriptor\$Branch")
        val descriptor =
            descriptorClass.constructors
                .single { it.parameterCount == 2 }
                .newInstance(branchInfo, displayName)

        return Class.forName("git4idea.ui.branch.dashboard.BranchTreeNode")
            .constructors
            .single { it.parameterCount == 1 }
            .newInstance(descriptor)
    }
}
