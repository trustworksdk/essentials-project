package com.example.post.letters.views.letter_log

import com.example.post.letters.events.LetterLost
import com.example.post.letters.events.LetterPosted
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.IgnoresEventTypes
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler

@IgnoresEventTypes(LetterLost::class)
class LetterLogProjection : ViewEventProcessor() {
    @MessageHandler
    fun on(e: LetterPosted) {
    }
}
