package com.brudcast.sample

import com.brudcast.sdk.BrudcastAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The actions the SDK handed to the sample, newest first, for the screen to show. */
object ActionLog {
    private const val MAX_ENTRIES = 20

    private val entries = MutableStateFlow<List<String>>(emptyList())

    val actions: StateFlow<List<String>> = entries.asStateFlow()

    /**
     * Adds an action to the log.
     *
     * @param action The action.
     */
    fun record(action: BrudcastAction) {
        val source = when (val origin = action.source) {
            is BrudcastAction.Source.InApp -> "in-app ${origin.messageId}"
            is BrudcastAction.Source.Push -> "push ${origin.messageId}"
        }
        val trust = if (action.verified) "" else ", unverified"
        val line = "${action.kind.name.lowercase()} ${action.value} ($source$trust)"
        entries.update { (listOf(line) + it).take(MAX_ENTRIES) }
    }
}
