package dev.applesideload.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Names that point at a person - an iPhone's name, a team named after its
 * owner, an Apple ID - learnt while the app runs, so that the anonymous log
 * collection can leave them out.
 *
 * [Redaction] removes anything credential shaped as lines are written; these
 * values are only known at run time, so they are removed on the way out
 * instead. The log is only held in memory, so the names learnt in this
 * process cover every line that can be sent.
 */
object PersonalData {
    private val known = ConcurrentHashMap<String, String>()

    /** Names iOS gives every device of a kind, which identify nobody. */
    private val generic = setOf("iphone", "ipad", "ipod", "ipod touch", "the iphone")

    fun remember(value: String?, placeholder: String) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.length < 3 || trimmed.lowercase() in generic) return
        known[trimmed] = placeholder
    }

    /** A developer team: a free account's is named after its owner, "Jane Doe (Personal Team)". */
    fun rememberTeam(name: String?, teamId: String?) {
        remember(name, "[team]")
        remember(name?.substringBefore(" ("), "[name]")
        remember(teamId, "[team-id]")
    }

    fun scrub(text: String): String {
        var out = text
        // Longest first, so a team name goes before the owner's name inside it.
        for ((value, placeholder) in known.entries.sortedByDescending { it.key.length }) {
            out = out.replace(value, placeholder, ignoreCase = true)
        }
        return out
    }
}
