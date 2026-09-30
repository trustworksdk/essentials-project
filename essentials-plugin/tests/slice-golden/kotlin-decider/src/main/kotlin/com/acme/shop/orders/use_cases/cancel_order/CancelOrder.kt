package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.routing.OrderCommand
import com.acme.shop.orders.types.OrderId

/**
 * Command for the cancel_order slice — the intent, as data.
 *
 * Implements [OrderCommand] so the `DeciderAndAggregateTypeConfigurator` can route it to the
 * Order aggregate and extract the aggregate id. Commands are NOT sealed: adding a command is
 * an open/closed extension (a new slice), never an edit to an existing hierarchy.
 */
data class CancelOrder(
    override val id: OrderId,
    // TODO: replace with this command's real payload
    val placeholder: String
) : OrderCommand
