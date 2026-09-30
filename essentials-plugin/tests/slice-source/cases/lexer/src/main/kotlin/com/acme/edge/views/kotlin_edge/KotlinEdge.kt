@file:Suppress("unused")

package com.acme.edge.views.kotlin_edge

import com.acme.edge.events.Pinged
import com.acme.edge.events.Ponged as Pong
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/* outer /* nested @MessageHandler fun on(e: Bogus) {} */ still a comment: @GetMapping("/phantom") } */

typealias Handled = Pinged

@RestController
class KotlinEdge {
    private val raw = """
        @MessageHandler fun on(e: Bogus) { "${'$'}{"}"}" }
        ${listOf("}", "{").joinToString { "\"$it\"" }}
    """
    private val template = "a ${if (raw.isEmpty()) "}" else "{"} b $raw"
    private val quote = '"'
    private val tick = '\''
    private val listener = object : Runnable {
        @MessageHandler
        fun on(event: Pinged) {
        }

        override fun run() {}
    }

    init {
        println("init { }")
    }

    constructor(flag: Boolean) : this()

    constructor()

    fun <T : Comparable<T>> List<T>.sortedEdge(): List<T> = sorted()

    @GetMapping("/edge")
    fun edge(): Map<String, List<String>> = when (raw.length) {
        0 -> mapOf("}" to listOf("{"))
        else -> emptyMap()
    }

    fun `name with spaces`(): String = KotlinEdge::class.java.simpleName

    /** RULE: a handler inside a companion object belongs to it. */
    companion object Factory {
        @MessageHandler
        fun on(event: Pong) {
        }
    }

    sealed class State {
        data class Open(val since: Long) : State()
        data object Closed : State()
    }

    enum class Level(val weight: Int) {
        LOW(1) {
            override fun label() = "low{"
        },
        HIGH(2);

        open fun label() = "level"
    }

    fun interface Callback {
        fun call(value: Handled)
    }

    @MessageHandler
    fun onAliased(event: Handled) {
    }
}
