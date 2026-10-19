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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_cancellation.RecordCancellation;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_fill.RecordFill;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An in-process exchange for the order core before the exchange-simulator adapter exists (design Q-7). It answers
 * at once and reports through {@link ExchangeReports} exactly as the adapter will: acceptance and fills after the
 * submission's answer, on its own. Per-instance memory only, which is enough for idempotent resubmission within one
 * process; a restart forgets the orders it took.
 */
@Component
public class StubExchangeGateway implements ExchangeGateway {
    private final StubExchangeProperties               properties;
    private final ExchangeReports                      reports;
    private final Map<OrderId, SubmissionResult>       taken   = new ConcurrentHashMap<>();
    private final Map<OrderId, OrderSubmission>        resting = new ConcurrentHashMap<>();

    public StubExchangeGateway(StubExchangeProperties properties, ExchangeReports reports) {
        this.properties = requireNonNull(properties, "No properties provided");
        this.reports = requireNonNull(reports, "No reports provided");
    }

    @Override
    public SubmissionResult submit(OrderSubmission submission) {
        requireNonNull(submission, "No submission provided");
        var known = taken.get(submission.orderId());
        if (known != null) {
            return known;
        }
        var refused = listed(properties.getRejectedInstruments(), submission);
        var result  = refused ? SubmissionResult.rejected("INSTRUMENT_NOT_TRADABLE") : SubmissionResult.taken(ExchangeOrderId.of("XO-" + submission.orderId()));
        taken.put(submission.orderId(), result);
        if (!refused) {
            reports.received(RecordExchangeAnswer.accepted(submission.orderId()));
            if (fills(submission)) {
                var tradeDate = LocalDate.now(properties.getZoneId());
                reports.received(new RecordFill(submission.orderId(),
                                                ExecutionId.of("EXE-" + submission.orderId()),
                                                submission.quantity(),
                                                submission.limitPrice(),
                                                tradeDate,
                                                BusinessDays.add(tradeDate, 1)));
            }
        }
        return result;
    }

    @Override
    public void cancel(OrderId orderId, ExchangeOrderId exchangeOrderId) {
        requireNonNull(orderId, "No orderId provided");
        // Only a resting order is left to cancel; a filled one is simply not cancelled, as at the real exchange
        if (resting.containsKey(orderId)) {
            reports.received(new RecordCancellation(orderId, "REQUESTED"));
        }
    }

    private boolean fills(OrderSubmission submission) {
        if (properties.getFillMode() == StubExchangeProperties.FillMode.NEVER || listed(properties.getRestingInstruments(), submission)) {
            resting.put(submission.orderId(), submission);
            return false;
        }
        return true;
    }

    private static boolean listed(List<String> instruments, OrderSubmission submission) {
        return instruments.stream().anyMatch(listed -> listed.equalsIgnoreCase(submission.instrumentId().toString()));
    }
}
