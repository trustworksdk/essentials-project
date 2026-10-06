package com.example.golden.config

import com.example.golden.orders.Order
import com.example.golden.orders.ORDERS
import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AggregatesConfig {
    @Bean
    fun aggregates(): EssentialsAggregateDeclarations =
        EssentialsAggregateDeclarations.builder().declare(ORDERS, Order::class.java).build()
}
