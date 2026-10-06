package com.example.lanes.catalog.use_cases.reprice;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;

/** TRAP: the words EventStore and AggregateType in this comment are not references. The import above is. */
public class RepriceHandler {
    private final EventStore eventStore;

    public RepriceHandler(EventStore eventStore) {
        this.eventStore = eventStore;
    }
}
