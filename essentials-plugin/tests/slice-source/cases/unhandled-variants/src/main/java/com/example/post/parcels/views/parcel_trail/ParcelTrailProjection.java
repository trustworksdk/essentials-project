package com.example.post.parcels.views.parcel_trail;

import com.example.post.parcels.events.ParcelEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class ParcelTrailProjection extends ViewEventProcessor {
    @MessageHandler
    void on(ParcelEvent e) {
    }
}
