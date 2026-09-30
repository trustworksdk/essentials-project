package com.acme.shop.orders.views.order_feed

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.coRouter

/** NOT ANALYSED: functional routes. The endpoint below is reported unverified, never "missing". */
@Configuration
class OrderFeedRoutes {
    @Bean
    fun feed() = coRouter {
        GET("/api/orders/feed") { ok().bodyValueAndAwait(emptyList<String>()) }
    }
}
