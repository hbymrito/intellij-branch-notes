package com.github.hbymrito.branchnotes

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

class BranchNotesRefreshListener(private val project: Project) :
    GitRepositoryChangeListener,
    VcsRepositoryMappingListener {

    override fun repositoryChanged(repository: GitRepository) {
        BranchNotesService.getInstance(project).reloadAsync()
    }

    override fun mappingChanged() {
        BranchNotesService.getInstance(project).reloadAsync()
    }
}
