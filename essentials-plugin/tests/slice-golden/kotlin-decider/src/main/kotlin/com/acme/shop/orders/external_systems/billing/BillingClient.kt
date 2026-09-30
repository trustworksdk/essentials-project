package com.acme.shop.orders.external_systems.billing

/**
 * Typed outbound port to the Billing system.
 *
 * An interface, so the publisher and the translator can be tested without the external system, and
 * so the transport (REST client, SDK, message producer) is swappable without touching the ACL.
 *
 * It speaks only external types — that is the point of the boundary.
 */
interface BillingClient {
    fun send(request: BillingRequest)
}
