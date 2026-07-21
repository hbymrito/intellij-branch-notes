package com.github.hbymrito.branchnotes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchNoteFormatterTest {
    @Test
    fun `displays note after branch name`() {
        assertEquals(
            "feature/user-profile [Add user profile page]",
            BranchNoteFormatter.display("feature/user-profile", "Add user profile page"),
        )
    }

    @Test
    fun `compacts whitespace for one-line UI`() {
        assertEquals("Add user profile", BranchNoteFormatter.compact("  Add\nuser\tprofile  "))
    }

    @Test
    fun `omits empty note`() {
        assertEquals("feature/test", BranchNoteFormatter.display("feature/test", "  "))
        assertNull(BranchNoteFormatter.compact("\n\t"))
    }

    @Test
    fun `round trips config key with dots and slashes`() {
        val branch = "feature/release.2026/user-profile"
        assertEquals(branch, BranchNoteFormatter.branchNameFromConfigKey(BranchNoteFormatter.configKey(branch)))
    }

    @Test
    fun `rejects unrelated config keys`() {
        assertNull(BranchNoteFormatter.branchNameFromConfigKey("branch.feature.remote"))
        assertNull(BranchNoteFormatter.branchNameFromConfigKey("branch..description"))
    }
}
