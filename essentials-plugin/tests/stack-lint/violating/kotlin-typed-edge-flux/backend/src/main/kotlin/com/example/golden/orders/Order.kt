package com.example.golden.orders

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy

// Declared in config/AggregatesConfig.kt, so the policy reaches its registry.
@AggregateSnapshotPolicy(everyNEvents = 100)
class Order
