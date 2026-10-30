package com.example.cart.stores.aggregates

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType

class Store {
    companion object {
        val AGGREGATE_TYPE: AggregateType = AggregateType.of("Stores")
    }

    fun close() {
    }
}
