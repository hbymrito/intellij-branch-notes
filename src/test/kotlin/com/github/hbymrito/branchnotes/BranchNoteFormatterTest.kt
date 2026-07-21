package com.github.hbymrito.branchnotes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchNoteFormatterTest {
    @Test
    fun `displays note after branch name`() {
        assertEquals(
            "feature/develop-YT03085 [导入线下病历]",
            BranchNoteFormatter.display("feature/develop-YT03085", "导入线下病历"),
        )
    }

    @Test
    fun `compacts whitespace for one-line UI`() {
        assertEquals("导入 线下 病历", BranchNoteFormatter.compact("  导入\n线下\t病历  "))
    }

    @Test
    fun `omits empty note`() {
        assertEquals("feature/test", BranchNoteFormatter.display("feature/test", "  "))
        assertNull(BranchNoteFormatter.compact("\n\t"))
    }

    @Test
    fun `round trips config key with dots and slashes`() {
        val branch = "feature/release.2026/YT03085"
        assertEquals(branch, BranchNoteFormatter.branchNameFromConfigKey(BranchNoteFormatter.configKey(branch)))
    }

    @Test
    fun `rejects unrelated config keys`() {
        assertNull(BranchNoteFormatter.branchNameFromConfigKey("branch.feature.remote"))
        assertNull(BranchNoteFormatter.branchNameFromConfigKey("branch..description"))
    }
}
