package com.github.hbymrito.branchnotes

import com.intellij.util.messages.Topic

fun interface BranchNotesListener {
    fun notesChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<BranchNotesListener> =
            Topic.create("Git branch notes changed", BranchNotesListener::class.java)
    }
}
