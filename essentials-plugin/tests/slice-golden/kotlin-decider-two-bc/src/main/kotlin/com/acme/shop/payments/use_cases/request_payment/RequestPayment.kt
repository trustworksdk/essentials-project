package com.acme.shop.payments.use_cases.request_payment

import com.acme.shop.payments.routing.PaymentCommand
import com.acme.shop.payments.types.PaymentId

/**
 * Command for the request_payment slice — the intent, as data.
 *
 * Implements [PaymentCommand] so the `DeciderAndAggregateTypeConfigurator` can route it to the
 * Payment aggregate and extract the aggregate id. Commands are NOT sealed: adding a command is
 * an open/closed extension (a new slice), never an edit to an existing hierarchy.
 */
data class RequestPayment(
    override val id: PaymentId,
    // TODO: replace with this command's real payload
    val placeholder: String
) : PaymentCommand
