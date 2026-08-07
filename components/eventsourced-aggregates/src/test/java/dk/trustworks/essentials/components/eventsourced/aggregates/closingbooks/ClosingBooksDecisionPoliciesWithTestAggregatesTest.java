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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.aggregates.CustomerId;
import dk.trustworks.essentials.components.eventsourced.aggregates.OrderId;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.Order;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingBooksDecisionPoliciesWithTestAggregatesTest {
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    @Test
    void accepted_modern_order_can_trigger_close_and_open_next_on_explicit_command() {
        var aggregate = acceptedModernOrder();
        var policy = ClosingBooksDecisionPolicies.<String, Order>closeAndOpenNextOnExplicitCommand(order -> order.accepted);

        var decision = policy.decide(context(aggregate, ClosingBooksTriggerMode.EXPLICIT_COMMAND));

        assertThat(decision).isEqualTo(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT);
    }

    @Test
    void accepted_modern_order_does_not_trigger_explicit_command_policy_on_access() {
        var aggregate = acceptedModernOrder();
        var policy = ClosingBooksDecisionPolicies.<String, Order>closeAndOpenNextOnExplicitCommand(order -> order.accepted);

        var decision = policy.decide(context(aggregate, ClosingBooksTriggerMode.ON_ACCESS));

        assertThat(decision).isEqualTo(ClosingBooksDecision.KEEP_OPEN);
    }

    @Test
    void accepted_modern_order_with_state_can_trigger_close_only_on_scheduled_scan() {
        var aggregate = acceptedModernOrderWithState();
        var policy = ClosingBooksDecisionPolicies.<String, dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order>closeOnlyOnScheduledScan(order -> order.state().accepted);

        var decision = policy.decide(context(aggregate, ClosingBooksTriggerMode.SCHEDULED_SCAN));

        assertThat(decision).isEqualTo(ClosingBooksDecision.CLOSE_ONLY);
    }

    @Test
    void non_accepted_order_keeps_the_generation_open() {
        var aggregate = new Order(OrderId.random(), CustomerId.random(), 1234);
        var policy = ClosingBooksDecisionPolicies.<String, Order>closeAndOpenNextOnExplicitCommand(order -> order.accepted);

        var decision = policy.decide(context(aggregate, ClosingBooksTriggerMode.EXPLICIT_COMMAND));

        assertThat(decision).isEqualTo(ClosingBooksDecision.KEEP_OPEN);
    }

    private Order acceptedModernOrder() {
        var aggregate = new Order(OrderId.random(), CustomerId.random(), 1234);
        aggregate.accept();
        return aggregate;
    }

    private dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order acceptedModernOrderWithState() {
        var aggregate = new dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order(OrderId.random(), CustomerId.random(), 1234);
        aggregate.accept();
        return aggregate;
    }

    private <AGGREGATE> ClosingBooksEvaluationContext<String, AGGREGATE> context(AGGREGATE aggregate,
                                                                                  ClosingBooksTriggerMode triggerMode) {
        return new ClosingBooksEvaluationContext<>(AGGREGATE_TYPE,
                                                   new LogicalAggregateId<>("Order-123"),
                                                   new AggregateGeneration<>(AGGREGATE_TYPE,
                                                                             new LogicalAggregateId<>("Order-123"),
                                                                             1L,
                                                                             "Order-123#1",
                                                                             GenerationState.OPEN,
                                                                             OffsetDateTime.parse("2026-03-01T00:00:00Z"),
                                                                             Optional.empty()),
                                                   aggregate,
                                                   triggerMode,
                                                   OffsetDateTime.parse("2026-03-29T00:00:00Z"));
    }
}
