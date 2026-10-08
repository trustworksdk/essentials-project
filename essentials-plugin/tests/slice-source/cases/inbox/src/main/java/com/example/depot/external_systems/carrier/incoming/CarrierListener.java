package com.example.depot.external_systems.carrier.incoming;

import com.example.depot.use_cases.flag_lost_parcel.FlagLostParcel;
import com.example.depot.use_cases.record_collection.RecordCollection;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.InboxConfig;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * RULE (dispatch): `collections` comes from getOrCreateInbox(config, commandBus), the overload that sends what the
 * inbox receives to the command bus, so RecordCollection is dispatched. Written `inboxes.addMessageReceived(...)`
 * here in a comment, it must not count.
 * TRAP (dispatch): `audit` has a message consumer of its own; what it receives goes nowhere near the bus.
 * RULE (unresolved): `lostParcels` is handed in, so where it forwards is not known here.
 * RULE (11(b)): the listener receives the supertype CarrierEvent; its subtype ParcelLost is not in consumes.
 * RULE (11(b)): the second listener receives a String, which is no project type: not compared, said so.
 */
public class CarrierListener {
    private final Inbox collections;
    private final Inbox audit;
    private final Inbox lostParcels;

    public CarrierListener(Inboxes inboxes, CommandBus commandBus, Inbox lostParcels) {
        this.collections = inboxes.getOrCreateInbox(InboxConfig.builder().build(), commandBus);
        this.audit = inboxes.getOrCreateInbox(InboxConfig.builder().build(), message -> { });
        this.lostParcels = lostParcels;
    }

    @KafkaListener(topics = "carrier-events")
    public void on(CarrierEvent event) {
        if (event instanceof ParcelCollected collected) {
            collections.addMessageReceived(new RecordCollection(collected.parcelId()));
            audit.addMessageReceived(new RecordCollection(collected.parcelId()));
        } else {
            lostParcels.addMessageReceived(new FlagLostParcel(event.parcelId()));
        }
    }

    @RabbitListener(queues = "carrier-notes")
    public void onNote(String note) {
    }
}
