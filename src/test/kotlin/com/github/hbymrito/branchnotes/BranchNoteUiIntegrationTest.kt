package com.github.hbymrito.branchnotes

import git4idea.actions.branch.GitSingleBranchAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BranchNoteUiIntegrationTest {
    @Test
    fun `branch note actions use the native single branch action contract`() {
        assertIs<GitSingleBranchAction>(EditBranchNoteAction())
        assertIs<GitSingleBranchAction>(DeleteBranchNoteAction())
    }

    @Test
    fun `status bar widget is disabled by default`() {
        assertFalse(BranchNotesWidgetFactory().isEnabledByDefault)
    }

    @Test
    fun `branch note actions are registered in the native Git branch group`() {
        val pluginXml =
            checkNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")).readText()

        assertContains(pluginXml, """id="BranchNotes.EditRemark"""")
        assertContains(pluginXml, """id="BranchNotes.DeleteRemark"""")
        assertContains(pluginXml, """group-id="Git.Branch"""")
    }

    @Test
    fun `push completion listener is registered`() {
        val pluginXml =
            checkNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")).readText()

        assertContains(pluginXml, """topic="git4idea.push.GitPushListener"""")
    }
}
