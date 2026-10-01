package com.example.shop.payments.use_cases.request_payment;

import com.example.shop.payments.routing.PaymentCommand;
import com.example.shop.payments.types.PaymentId;

/**
 * Command for the request_payment slice — the intent, as data.
 *
 * A {@code record}: commands are immutable value objects. Implements {@link PaymentCommand} so
 * the {@code EventStreamDeciderAndAggregateTypeConfigurator} can route it to the Payment
 * aggregate and extract the aggregate id.
 *
 * The command interface is deliberately NOT sealed: adding a command is an open/closed extension
 * (a new slice), never an edit to an existing hierarchy.
 */
public record RequestPayment(
        PaymentId id,
        // TODO: replace with this command's real payload
        String placeholder
) implements PaymentCommand {
}
