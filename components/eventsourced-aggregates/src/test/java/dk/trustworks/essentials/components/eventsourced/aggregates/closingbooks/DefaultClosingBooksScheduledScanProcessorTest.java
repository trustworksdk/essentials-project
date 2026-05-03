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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultClosingBooksScheduledScanProcessorTest {
    @Test
    void scheduled_scan_can_close_an_accepted_order_generation() {
        var aggregateType = AggregateType.of("Orders");
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC));
        var currentGeneration = coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);

        var acceptedOrder = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();

        var meterRegistry = new SimpleMeterRegistry();
        var processor = new DefaultClosingBooksScheduledScanProcessor<>(aggregateType,
                                                                        repository,
                                                                        streamAggregateId -> streamAggregateId.equals(currentGeneration.streamAggregateId())
                                                                                ? java.util.Optional.of(acceptedOrder)
                                                                                : java.util.Optional.empty(),
                                                                        ClosingBooksDecisionPolicies.<String, Order>closeOnlyOnScheduledScan(order -> order.accepted),
                                                                        coordinator,
                                                                        java.util.Optional.of(meterRegistry));

        var processedCount = processor.processNextBatch(10);

        assertThat(processedCount).isEqualTo(1);
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId)).isEmpty();
        assertThat(repository.loadGenerations(aggregateType, logicalAggregateId).getFirst().isClosed()).isTrue();
        assertThat(meterRegistry.find(ClosingBooksManagementMeasurementSupport.METRIC_PREFIX + ".scan.process_generation.outcome")
                                .tag("aggregate_type", aggregateType.toString())
                                .tag("outcome", "close_only")
                                .counter())
                .isNotNull();
    }

    @Test
    void scheduled_scan_can_roll_forward_an_accepted_order_generation() {
        var aggregateType = AggregateType.of("Orders");
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC));
        var currentGeneration = coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);

        var acceptedOrder = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();

        var processor = new DefaultClosingBooksScheduledScanProcessor<>(aggregateType,
                                                                        repository,
                                                                        streamAggregateId -> streamAggregateId.equals(currentGeneration.streamAggregateId())
                                                                                ? java.util.Optional.of(acceptedOrder)
                                                                                : java.util.Optional.empty(),
                                                                        ClosingBooksDecisionPolicies.<String, Order>anyOf(
                                                                                ClosingBooksDecisionPolicies.closeAndOpenNextOnExplicitCommand(order -> false),
                                                                                ClosingBooksDecisionPolicies.closeAndOpenNextWhenAggregate(order -> order.accepted)),
                                                                        coordinator);

        var processedCount = processor.processNextBatch(10);

        assertThat(processedCount).isEqualTo(1);
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId)).hasValueSatisfying(openGeneration -> {
            assertThat(openGeneration.generation()).isEqualTo(2L);
            assertThat(openGeneration.streamAggregateId()).isEqualTo("Order-123#2");
        });
    }
}
