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

package dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer;

import dk.trustworks.essentials.examples.trading.orders.types.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the exchange said about an order's submission: taken ({@code exchangeOrderId}), live, or refused
 * ({@code rejectReason}). Sent by the exchange translation slice, never by a customer.
 */
public record RecordExchangeAnswer(OrderId orderId,
                                   Answer answer,
                                   ExchangeOrderId exchangeOrderId,
                                   String rejectReason) {
    public enum Answer {
        SUBMITTED,
        ACCEPTED,
        REJECTED
    }

    public RecordExchangeAnswer {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(answer, "No answer provided");
        if (answer == Answer.SUBMITTED) {
            requireNonNull(exchangeOrderId, "No exchangeOrderId provided");
        }
        if (answer == Answer.REJECTED) {
            requireNonNull(rejectReason, "No rejectReason provided");
        }
    }

    public static RecordExchangeAnswer submitted(OrderId orderId, ExchangeOrderId exchangeOrderId) {
        return new RecordExchangeAnswer(orderId, Answer.SUBMITTED, exchangeOrderId, null);
    }

    public static RecordExchangeAnswer accepted(OrderId orderId) {
        return new RecordExchangeAnswer(orderId, Answer.ACCEPTED, null, null);
    }

    public static RecordExchangeAnswer rejected(OrderId orderId, String reason) {
        return new RecordExchangeAnswer(orderId, Answer.REJECTED, null, reason);
    }
}
