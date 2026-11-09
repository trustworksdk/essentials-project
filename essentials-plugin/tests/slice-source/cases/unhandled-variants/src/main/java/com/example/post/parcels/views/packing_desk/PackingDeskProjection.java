package com.example.post.parcels.views.packing_desk;

import com.example.post.parcels.events.PackedFully;
import com.example.post.parcels.events.ParcelPacked;
import com.example.post.parcels.events.ParcelEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.IgnoresEventTypes;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

@IgnoresEventTypes(value = {ParcelEvent.class}, eventNames = {"LegacyParcelNote"})
public class PackingDeskProjection extends ViewEventProcessor {
    @MessageHandler
    void on(PackedFully e) {
    }
}
