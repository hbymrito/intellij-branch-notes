package com.github.hbymrito.branchnotes

import git4idea.push.GitPushRepoResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchNotePushInheritanceTest {
    @Test
    fun `maps a newly pushed local branch to its exact remote branch`() {
        assertEquals(
            PushedBranchNoteTarget(
                localBranchName = "feature/login",
                remoteBranchName = "origin/review/login",
            ),
            branchNoteTarget(
                type = GitPushRepoResult.Type.NEW_BRANCH,
                sourceBranch = "refs/heads/feature/login",
                targetBranch = "refs/remotes/origin/review/login",
                targetRemote = "origin",
            ),
        )
    }

    @Test
    fun `ignores pushes to an existing remote branch`() {
        assertNull(
            branchNoteTarget(
                type = GitPushRepoResult.Type.SUCCESS,
                sourceBranch = "refs/heads/feature/login",
                targetBranch = "refs/remotes/origin/feature/login",
                targetRemote = "origin",
            )
        )
    }

    @Test
    fun `ignores detached revisions and tags`() {
        assertNull(
            branchNoteTarget(
                type = GitPushRepoResult.Type.NEW_BRANCH,
                sourceBranch = "a1b2c3d4",
                targetBranch = "refs/remotes/origin/feature/login",
                targetRemote = "origin",
            )
        )
        assertNull(
            branchNoteTarget(
                type = GitPushRepoResult.Type.NEW_BRANCH,
                sourceBranch = "refs/tags/v1.0.0",
                targetBranch = "refs/remotes/origin/v1.0.0",
                targetRemote = "origin",
            )
        )
    }

    @Test
    fun `rejects a target that does not belong to the reported remote`() {
        assertNull(
            branchNoteTarget(
                type = GitPushRepoResult.Type.NEW_BRANCH,
                sourceBranch = "refs/heads/feature/login",
                targetBranch = "refs/remotes/upstream/feature/login",
                targetRemote = "origin",
            )
        )
    }

    @Test
    fun `inherits a compact source note only when the target has no note`() {
        assertEquals("Local note", noteToInherit("  Local\nnote  ", null))
        assertNull(noteToInherit(null, null))
        assertNull(noteToInherit("Local note", "Remote note"))
    }
}
