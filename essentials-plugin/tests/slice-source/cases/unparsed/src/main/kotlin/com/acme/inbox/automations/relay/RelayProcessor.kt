package com.acme.inbox.automations.relay

import com.acme.inbox.events.MessageReceived
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.reactive.command.CommandBus

class RelayProcessor(private val commandBus: CommandBus, private val translator: RelayTranslator) : EventProcessor() {

    @MessageHandler
    fun on(event: MessageReceived) {
        // UNRESOLVED (reported, exit unaffected): the translator returns Any, so the command type is unknown.
        commandBus.send(translator.toCommand(event))
    }

    /** UNPARSED: a handler with no parameter has no event type to read. */
    @MessageHandler
    fun heartbeat() {
    }
}

class RelayTranslator {
    fun toCommand(event: MessageReceived): Any = event
}
