package com.github.hbymrito.branchnotes

import git4idea.push.GitPushRepoResult

internal data class PushedBranchNoteTarget(
    val localBranchName: String,
    val remoteBranchName: String,
)

internal fun GitPushRepoResult.branchNoteTarget(): PushedBranchNoteTarget? =
    branchNoteTarget(type, sourceBranch, targetBranch, targetRemote)

internal fun branchNoteTarget(
    type: GitPushRepoResult.Type,
    sourceBranch: String,
    targetBranch: String,
    targetRemote: String,
): PushedBranchNoteTarget? {
    if (type != GitPushRepoResult.Type.NEW_BRANCH) return null

    val localBranchName = sourceBranch.removePrefix(LOCAL_REF_PREFIX)
    if (localBranchName == sourceBranch || localBranchName.isEmpty()) return null

    val remoteBranchName = targetBranch.removePrefix(REMOTE_REF_PREFIX)
    if (
        remoteBranchName == targetBranch ||
            remoteBranchName.isEmpty() ||
            !remoteBranchName.startsWith("$targetRemote/")
    ) {
        return null
    }

    return PushedBranchNoteTarget(localBranchName, remoteBranchName)
}

internal fun noteToInherit(sourceNote: String?, targetNote: String?): String? {
    val compactSourceNote = BranchNoteFormatter.compact(sourceNote) ?: return null
    return compactSourceNote.takeIf { BranchNoteFormatter.compact(targetNote) == null }
}

private const val LOCAL_REF_PREFIX = "refs/heads/"
private const val REMOTE_REF_PREFIX = "refs/remotes/"
