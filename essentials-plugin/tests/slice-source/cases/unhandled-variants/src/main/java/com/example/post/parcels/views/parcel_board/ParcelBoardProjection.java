package com.example.post.parcels.views.parcel_board;

import com.example.post.parcels.events.ParcelDelivered;
import com.example.post.parcels.events.ParcelRegistered;
import com.example.post.parcels.events.ParcelShipped;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.IgnoresEventTypes;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;

@IgnoresEventTypes({ParcelDelivered.class})
public class ParcelBoardProjection extends ViewEventProcessor {
    @MessageHandler
    void on(ParcelRegistered e) {
    }

    @MessageHandler
    void on(ParcelShipped e, OrderedMessage message) {
    }
}
