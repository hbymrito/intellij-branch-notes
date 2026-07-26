package com.github.hbymrito.branchnotes

import com.intellij.testFramework.LightVirtualFile
import git4idea.repo.GitRepository
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BranchNotesModelTest {
    @Test
    fun `keeps local and remote notes separate for the same branch name`() {
        val repository = repository("repo")
        val local =
            BranchNoteEntry(
                repository,
                "origin/main",
                BranchRefType.LOCAL,
                "Local note",
                true,
            )
        val remote =
            BranchNoteEntry(
                repository,
                "origin/main",
                BranchRefType.REMOTE,
                "Remote note",
                false,
            )
        val snapshot = BranchNotesSnapshot(listOf(local, remote))

        assertEquals(
            "Local note",
            snapshot.noteFor("origin/main", BranchRefType.LOCAL, repository.root.path),
        )
        assertEquals(
            "Remote note",
            snapshot.noteFor("origin/main", BranchRefType.REMOTE, repository.root.path),
        )
        assertEquals(listOf(local), snapshot.localEntries)
        assertSame(local, snapshot.current)
    }

    @Test
    fun `shows an aggregated note only when repositories agree`() {
        val first = repository("first")
        val second = repository("second")
        val matching =
            BranchNotesSnapshot(
                listOf(
                    remoteEntry(first, "Shared note"),
                    remoteEntry(second, "Shared note"),
                )
            )
        val different =
            BranchNotesSnapshot(
                listOf(
                    remoteEntry(first, "First note"),
                    remoteEntry(second, "Second note"),
                )
            )

        assertEquals(
            "Shared note",
            matching.noteFor("origin/main", BranchRefType.REMOTE, null),
        )
        assertNull(different.noteFor("origin/main", BranchRefType.REMOTE, null))
    }

    @Test
    fun `detects new local branches only in repositories that already had a baseline`() {
        val knownRepository = repository("known")
        val newlyMappedRepository = repository("newly-mapped")
        val previous =
            BranchNotesSnapshot(
                listOf(localEntry(knownRepository, "main"))
            )
        val newBranch = localEntry(knownRepository, "feature/login")
        val current =
            BranchNotesSnapshot(
                listOf(
                    localEntry(knownRepository, "main"),
                    newBranch,
                    localEntry(newlyMappedRepository, "existing"),
                )
            )

        assertEquals(
            listOf(newBranch),
            current.newLocalEntriesSince(previous, setOf(knownRepository.root.path)),
        )
        assertEquals(
            emptyList(),
            current.newLocalEntriesSince(BranchNotesSnapshot.EMPTY, emptySet()),
        )
    }

    @Test
    fun `detects new remote branches only in repositories that already had a baseline`() {
        val knownRepository = repository("known")
        val newlyMappedRepository = repository("newly-mapped")
        val existingRemote = remoteEntry(knownRepository, null)
        val newRemote = existingRemote.copy(branchName = "origin/feature/login")
        val current =
            BranchNotesSnapshot(
                listOf(
                    existingRemote,
                    newRemote,
                    remoteEntry(newlyMappedRepository, null),
                )
            )

        assertEquals(
            listOf(newRemote),
            current.newRemoteEntriesSince(
                BranchNotesSnapshot(listOf(existingRemote)),
                setOf(knownRepository.root.path),
            ),
        )
        assertEquals(
            emptyList(),
            current.newRemoteEntriesSince(BranchNotesSnapshot.EMPTY, emptySet()),
        )
    }

    @Test
    fun `inherits a tracked remote note without overriding a local note`() {
        val repository = repository("repo")
        val remote = remoteEntry(repository, "Remote note")
        val local = localEntry(repository, "feature/login")
        val localWithNote = local.copy(note = "Local note")
        val snapshot = BranchNotesSnapshot(listOf(remote, local, localWithNote))

        assertEquals(
            "Remote note",
            snapshot.remoteNoteToInherit(local, "origin/main"),
        )
        assertNull(snapshot.remoteNoteToInherit(localWithNote, "origin/main"))
        assertNull(snapshot.remoteNoteToInherit(local, "origin/missing"))
        assertNull(snapshot.remoteNoteToInherit(remote, "origin/main"))
    }

    @Test
    fun `indexes each local tracking branch once and keeps the first duplicate target`() {
        val firstRepository = repository("first")
        val secondRepository = repository("second")
        val firstLocal = localEntry(firstRepository, "feature/first")
        val duplicateLocal = localEntry(firstRepository, "feature/duplicate")
        val secondLocal = localEntry(secondRepository, "feature/second")
        var trackingLookups = 0

        val index =
            indexTrackedLocalEntriesByRemote(
                listOf(firstLocal, duplicateLocal, secondLocal)
            ) { entry ->
                trackingLookups += 1
                if (entry === secondLocal) "upstream/main" else "origin/main"
            }

        assertEquals(3, trackingLookups)
        assertSame(
            firstLocal,
            index[RemoteBranchKey(firstRepository.root.path, "origin/main")],
        )
        assertSame(
            secondLocal,
            index[RemoteBranchKey(secondRepository.root.path, "upstream/main")],
        )
        assertEquals(2, index.size)
    }

    private fun localEntry(
        repository: GitRepository,
        branchName: String,
    ) =
        BranchNoteEntry(
            repository,
            branchName,
            BranchRefType.LOCAL,
            null,
            false,
        )

    private fun remoteEntry(repository: GitRepository, note: String?) =
        BranchNoteEntry(
            repository,
            "origin/main",
            BranchRefType.REMOTE,
            note,
            false,
        )

    private fun repository(name: String): GitRepository {
        val root = LightVirtualFile(name)
        return Proxy.newProxyInstance(
            GitRepository::class.java.classLoader,
            arrayOf(GitRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getRoot" -> root
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "GitRepository($name)"
                else -> null
            }
        } as GitRepository
    }
}
