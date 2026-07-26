package com.github.hbymrito.branchnotes

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.project.Project
import git4idea.push.GitPushListener
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

class BranchNotesRefreshListener(private val project: Project) :
    GitRepositoryChangeListener,
    VcsRepositoryMappingListener,
    GitPushListener {

    override fun repositoryChanged(repository: GitRepository) {
        BranchNotesService.getInstance(project).reloadAsync()
    }

    override fun mappingChanged() {
        BranchNotesService.getInstance(project).reloadAsync()
    }

    override fun onCompleted(repository: GitRepository, pushResult: GitPushRepoResult) {
        BranchNotesService.getInstance(project).inheritLocalNoteAfterPush(repository, pushResult)
    }
}
