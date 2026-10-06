package com.example.inbox.views.broken_view;

import com.example.inbox.events.MessageReceived;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

/** UNPARSED: the class body is never closed, so nothing in this file can be read. */
public class BrokenProjection {

    @MessageHandler
    void on(MessageReceived event) {
        if (event != null) {
    }
}
