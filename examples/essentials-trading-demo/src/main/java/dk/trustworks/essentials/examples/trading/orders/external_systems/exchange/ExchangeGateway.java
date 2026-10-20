/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import dk.trustworks.essentials.examples.trading.orders.types.*;

/**
 * The brokerage's door to the exchange. Blocking calls, made with no unit of work active.
 * <p>
 * Everything the exchange reports afterwards — acceptance, fills, cancellations — does not come back from these calls
 * but arrives on its own, through {@link ExchangeReports}. The stub ({@link StubExchangeGateway}) reports directly;
 * the exchange-simulator adapter will report through its signed webhooks and event-log catch-up.
 */
public interface ExchangeGateway {
    /**
     * Idempotent per {@code orderId} (the exchange's {@code clientOrderId}, D8): a resubmission after a redelivery answers
     * as the first submission did.
     *
     * @throws ExchangeUnavailableException the exchange could not be reached; resubmitting is safe
     */
    SubmissionResult submit(OrderSubmission submission);

    /**
     * Asks the exchange to cancel what is left of an order, named by our {@code orderId} - the exchange's
     * {@code clientOrderId}, as a FIX {@code OrderCancelRequest} names it by {@code OrigClOrdID}. The cancellation itself
     * is reported through {@link ExchangeReports}; an order that already filled is simply not cancelled.
     * <p>
     * Not by the exchange's own order id: that is recorded on the order only once the submission's answer has gone
     * through {@link ExchangeReports}, and a cancel requested a moment after placing the order would have to wait for it.
     * An adapter for an exchange that cancels only by its own id resolves it itself - by the cancel-by-client-order-id
     * of SR-30 where the exchange has it, else by resubmitting the order, which the exchange answers idempotently, with
     * its id.
     */
    void cancel(OrderId orderId);
}
