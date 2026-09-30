package com.acme.shop.payments.routing

import com.acme.shop.payments.types.PaymentId

/**
 * Aggregate routing interface for the Payment aggregate (BC-private).
 *
 * Every concrete command — each in its own command slice under `use_cases/` — implements this
 * marker so the `DeciderAndAggregateTypeConfigurator` can route it to the Payment aggregate
 * and extract the aggregate id.
 *
 * Routing interfaces live in `routing/`, never in `use_cases/`. This interface is deliberately NOT
 * sealed: adding a command is an open/closed extension, so no existing file changes.
 */
interface PaymentCommand {
    val id: PaymentId
}
