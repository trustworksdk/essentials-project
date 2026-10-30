package com.example.post.parcels.automations.notify_sender;

import com.example.post.parcels.events.ParcelShipped;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class NotifySenderProcessor extends EventProcessor {
    @MessageHandler
    void on(ParcelShipped e) {
    }
}
