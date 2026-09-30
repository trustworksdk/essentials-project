@file:JvmName("OrdersConfig")

package com.acme.shop.orders.config

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import org.springframework.context.annotation.Configuration

@Configuration
class OrdersConfiguration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE: AggregateType = AggregateType.of("Orders")
    }
}
