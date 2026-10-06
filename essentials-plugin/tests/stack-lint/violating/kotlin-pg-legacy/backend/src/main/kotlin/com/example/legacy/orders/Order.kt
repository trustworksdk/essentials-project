package com.example.legacy.orders

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy

@AggregateSnapshotPolicy(everyNEvents = 100)
class Order
