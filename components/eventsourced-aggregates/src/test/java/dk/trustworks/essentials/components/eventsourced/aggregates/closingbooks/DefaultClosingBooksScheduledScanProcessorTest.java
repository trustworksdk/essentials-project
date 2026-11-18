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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.*;

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

    /**
     * EF-1b: with a rollover the scan rolls as the application does on access - here the hook stands in for the
     * application's closing event and next-generation factory - instead of switching only the generation row.
     */
    @Test
    void a_scan_rolls_with_the_applications_rollover_when_one_is_set() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository         = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        clock);
        var currentGeneration = coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var acceptedOrder     = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();
        var rolledWith = new java.util.ArrayList<ClosingBooksEvaluationContext<String, Order>>();

        var processor = DefaultClosingBooksScheduledScanProcessor.<String, Order>builder()
                                                                 .setAggregateType(aggregateType)
                                                                 .setGenerationRepository(repository)
                                                                 .setAggregateLoader(streamAggregateId -> java.util.Optional.of(acceptedOrder))
                                                                 .setPolicy(ClosingBooksDecisionPolicies.closeAndOpenNextWhenAggregate(order -> order.accepted))
                                                                 .setCoordinator(coordinator)
                                                                 .setClock(clock)
                                                                 .setScanRetryDelay(Duration.ofMinutes(5))
                                                                 .setScanRollover(context -> {
                                                                     rolledWith.add(context);
                                                                     coordinator.closeAndOpenNextGeneration(context.logicalAggregateId());
                                                                 })
                                                                 .build();

        assertThat(processor.processNextBatch(10)).isEqualTo(1);

        assertThat(rolledWith).singleElement().satisfies(context -> {
            assertThat(context.triggerMode()).isEqualTo(ClosingBooksTriggerMode.SCHEDULED_SCAN);
            assertThat(context.currentGeneration().generation()).isEqualTo(currentGeneration.generation());
            assertThat(context.aggregate()).isSameAs(acceptedOrder);
        });
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId))
                .hasValueSatisfying(open -> assertThat(open.generation()).isEqualTo(2L));
    }

    /**
     * F-009: an on-access rollover can move generation 1 to 2 between the scan reading its batch and taking the
     * generation lock. The scan must not then decide on, or roll, generation 2 with what it knows of generation 1 -
     * that would close the fresh generation 2 and open a 3 built from the stale aggregate.
     */
    @Test
    void a_scan_does_not_act_on_a_generation_that_rolled_after_the_scan_read_it() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var coordinatorHolder  = new java.util.concurrent.atomic.AtomicReference<ClosingBooksCoordinator<String>>();
        // Hands out the batch, then rolls the generation as a command arriving at the same moment would.
        var repository = new InMemoryClosingBooksGenerationResolver<String>() {
            @Override
            public java.util.List<AggregateGeneration<String>> loadOpenGenerations(AggregateType type, int limit, OffsetDateTime eligibleAt) {
                var batch = super.loadOpenGenerations(type, limit, eligibleAt);
                batch.forEach(generation -> coordinatorHolder.get().closeAndOpenNextGeneration(generation.logicalAggregateId()));
                return batch;
            }
        };
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        clock);
        coordinatorHolder.set(coordinator);
        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var loadedStreams = new java.util.ArrayList<String>();
        var rolledWith    = new java.util.ArrayList<ClosingBooksEvaluationContext<String, Order>>();
        var meterRegistry = new SimpleMeterRegistry();

        var processor = DefaultClosingBooksScheduledScanProcessor.<String, Order>builder()
                                                                 .setAggregateType(aggregateType)
                                                                 .setGenerationRepository(repository)
                                                                 .setAggregateLoader(streamAggregateId -> {
                                                                     loadedStreams.add(streamAggregateId);
                                                                     var order = new Order(OrderId.random(), CustomerId.random(), 1234);
                                                                     order.accept();
                                                                     return java.util.Optional.of(order);
                                                                 })
                                                                 .setPolicy(context -> ClosingBooksDecision.CLOSE_AND_OPEN_NEXT)
                                                                 .setCoordinator(coordinator)
                                                                 .setClock(clock)
                                                                 .setScanRetryDelay(Duration.ofMinutes(5))
                                                                 .setMeterRegistry(meterRegistry)
                                                                 .setScanRollover(context -> {
                                                                     rolledWith.add(context);
                                                                     coordinator.closeAndOpenNextGeneration(context.logicalAggregateId());
                                                                 })
                                                                 .build();

        processor.processNextBatch(10);

        assertThat(rolledWith).describedAs("no rollover acts on a generation the scan did not read").isEmpty();
        assertThat(loadedStreams).describedAs("the stale generation's aggregate is never loaded").doesNotContain("Order-123#1");
        assertThat(repository.loadGenerations(aggregateType, logicalAggregateId)).describedAs("no generation 3")
                                                                                 .extracting(AggregateGeneration::generation)
                                                                                 .containsExactly(1L, 2L);
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId))
                .hasValueSatisfying(open -> assertThat(open.generation()).isEqualTo(2L));
        assertThat(meterRegistry.find(ClosingBooksManagementMeasurementSupport.METRIC_PREFIX + ".scan.process_generation.outcome")
                                .tag("outcome", "generation_moved")
                                .counter())
                .isNotNull();
    }

    /**
     * F-008: the rollover records the aggregate's closing event on {@code context.aggregate()}, and a stateful
     * repository flushes only the aggregates registered with the unit of work that commits. So the aggregate must be
     * loaded in the unit of work the rollover runs in, not in one of its own that has already ended.
     */
    @Test
    void a_scan_loads_the_aggregate_in_the_unit_of_work_its_rollover_runs_in() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository         = new InMemoryClosingBooksGenerationResolver<String>();
        var unitOfWorkFactory  = InlineUnitOfWorkFactories.joining();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        unitOfWorkFactory,
                                                        clock);
        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var acceptedOrder = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();
        var loadedIn   = new java.util.ArrayList<java.util.Optional<?>>();
        var rolledIn   = new java.util.ArrayList<java.util.Optional<?>>();

        var processor = DefaultClosingBooksScheduledScanProcessor.<String, Order>builder()
                                                                 .setAggregateType(aggregateType)
                                                                 .setGenerationRepository(repository)
                                                                 .setAggregateLoader(streamAggregateId -> {
                                                                     loadedIn.add(unitOfWorkFactory.getCurrentUnitOfWork());
                                                                     return java.util.Optional.of(acceptedOrder);
                                                                 })
                                                                 .setPolicy(ClosingBooksDecisionPolicies.closeAndOpenNextWhenAggregate(order -> order.accepted))
                                                                 .setCoordinator(coordinator)
                                                                 .setClock(clock)
                                                                 .setScanRetryDelay(Duration.ofMinutes(5))
                                                                 .setScanRollover(context -> {
                                                                     rolledIn.add(unitOfWorkFactory.getCurrentUnitOfWork());
                                                                     coordinator.closeAndOpenNextGeneration(context.logicalAggregateId());
                                                                 })
                                                                 .build();

        assertThat(processor.processNextBatch(10)).isEqualTo(1);

        assertThat(loadedIn).singleElement().satisfies(unitOfWork -> assertThat(unitOfWork).isPresent());
        assertThat(rolledIn).singleElement().isEqualTo(loadedIn.getFirst());
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId))
                .hasValueSatisfying(open -> assertThat(open.generation()).isEqualTo(2L));
    }

    /**
     * The loader runs in the scan's unit of work, so a nested {@code withUnitOfWork} that throws marks it rollback-only
     * even when the loader catches the exception. An empty result is still a missing aggregate.
     */
    @Test
    void a_loader_that_catches_a_nested_unit_of_work_failure_and_returns_empty_counts_as_aggregate_missing() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository         = new InMemoryClosingBooksGenerationResolver<String>();
        var unitOfWorkFactory  = InlineUnitOfWorkFactories.joining();
        var meterRegistry      = new SimpleMeterRegistry();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        unitOfWorkFactory,
                                                        clock);
        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);

        var processor = DefaultClosingBooksScheduledScanProcessor.<String, Order>builder()
                                                                 .setAggregateType(aggregateType)
                                                                 .setGenerationRepository(repository)
                                                                 .setAggregateLoader(streamAggregateId -> {
                                                                     try {
                                                                         return unitOfWorkFactory.withUnitOfWork(uow -> {
                                                                             throw new IllegalStateException("no aggregate " + streamAggregateId);
                                                                         });
                                                                     } catch (RuntimeException e) {
                                                                         return Optional.empty();
                                                                     }
                                                                 })
                                                                 .setPolicy(ClosingBooksDecisionPolicies.closeAndOpenNextWhenAggregate(order -> order.accepted))
                                                                 .setCoordinator(coordinator)
                                                                 .setClock(clock)
                                                                 .setMeterRegistry(meterRegistry)
                                                                 .setScanRetryDelay(Duration.ofMinutes(5))
                                                                 .build();

        assertThat(processor.processNextBatch(10)).isZero();

        assertThat(processOutcomeCount(meterRegistry, "aggregate_missing")).isEqualTo(1.0d);
        assertThat(processOutcomeCount(meterRegistry, "failed")).isZero();
        assertThat(repository.loadOpenGenerations(aggregateType, 10, OffsetDateTime.now(clock)))
                .describedAs("deferred, so it does not head every batch")
                .isEmpty();
    }

    /**
     * A loader that catches a nested unit-of-work failure but still returns an aggregate leaves the scan's unit of work
     * rollback-only: a decision or a rollover made in it could never commit. The scan must not count it as decided.
     */
    @Test
    void a_loader_that_leaves_the_unit_of_work_rollback_only_is_counted_as_failed_and_nothing_is_decided() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository         = new InMemoryClosingBooksGenerationResolver<String>();
        var unitOfWorkFactory  = InlineUnitOfWorkFactories.joining();
        var meterRegistry      = new SimpleMeterRegistry();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        unitOfWorkFactory,
                                                        clock);
        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var acceptedOrder = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();
        var rollovers = new AtomicInteger();
        var caughtByLoader = new AtomicReference<RuntimeException>();

        var processor = DefaultClosingBooksScheduledScanProcessor.<String, Order>builder()
                                                                 .setAggregateType(aggregateType)
                                                                 .setGenerationRepository(repository)
                                                                 .setAggregateLoader(streamAggregateId -> {
                                                                     try {
                                                                         unitOfWorkFactory.withUnitOfWork(uow -> {
                                                                             throw new IllegalStateException("snapshot load failed for " + streamAggregateId);
                                                                         });
                                                                     } catch (RuntimeException e) {
                                                                         // falls back to an aggregate it has at hand
                                                                         caughtByLoader.set(e);
                                                                     }
                                                                     return Optional.of(acceptedOrder);
                                                                 })
                                                                 .setPolicy(ClosingBooksDecisionPolicies.closeAndOpenNextWhenAggregate(order -> order.accepted))
                                                                 .setCoordinator(coordinator)
                                                                 .setClock(clock)
                                                                 .setMeterRegistry(meterRegistry)
                                                                 .setScanRetryDelay(Duration.ofMinutes(5))
                                                                 .setScanRollover(context -> {
                                                                     rollovers.incrementAndGet();
                                                                     coordinator.closeAndOpenNextGeneration(context.logicalAggregateId());
                                                                 })
                                                                 .build();

        assertThat(processor.processNextBatch(10)).isZero();

        assertThat(caughtByLoader.get())
                .describedAs("the loader caught its own nested unit-of-work failure")
                .isNotNull();
        assertThat(rollovers).hasValue(0);
        assertThat(processOutcomeCount(meterRegistry, "failed")).isEqualTo(1.0d);
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId))
                .hasValueSatisfying(open -> assertThat(open.generation()).isEqualTo(1L));
        assertThat(repository.loadOpenGenerations(aggregateType, 10, OffsetDateTime.now(clock)))
                .describedAs("deferred, so it does not head every batch")
                .isEmpty();
    }

    private static double processOutcomeCount(SimpleMeterRegistry meterRegistry, String outcome) {
        var counter = meterRegistry.find(ClosingBooksManagementMeasurementSupport.METRIC_PREFIX + ".scan.process_generation.outcome")
                                   .tag("outcome", outcome)
                                   .counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Test
    void a_rollover_that_fails_leaves_the_generation_open_and_defers_it() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository         = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        clock);
        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var acceptedOrder = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();

        var processor = DefaultClosingBooksScheduledScanProcessor.<String, Order>builder()
                                                                 .setAggregateType(aggregateType)
                                                                 .setGenerationRepository(repository)
                                                                 .setAggregateLoader(streamAggregateId -> java.util.Optional.of(acceptedOrder))
                                                                 .setPolicy(ClosingBooksDecisionPolicies.closeAndOpenNextWhenAggregate(order -> order.accepted))
                                                                 .setCoordinator(coordinator)
                                                                 .setClock(clock)
                                                                 .setScanRetryDelay(Duration.ofMinutes(5))
                                                                 .setScanRollover(context -> {
                                                                     throw new IllegalStateException("the next-generation factory failed");
                                                                 })
                                                                 .build();

        assertThat(processor.processNextBatch(10)).isZero();

        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId))
                .hasValueSatisfying(open -> assertThat(open.generation()).isEqualTo(1L));
        assertThat(repository.loadOpenGenerations(aggregateType, 10, OffsetDateTime.now(clock)))
                .describedAs("deferred, so it does not head every batch")
                .isEmpty();
    }

    /**
     * A batch is the oldest N open generations, so a generation the scan can never make progress on used to sit at the
     * head of every batch forever. With a batch size of 1 that starves the younger generation completely.
     */
    @Test
    void a_generation_whose_aggregate_cannot_be_loaded_is_deferred_so_it_does_not_starve_the_others() {
        var aggregateType = AggregateType.of("Orders");
        var clock         = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var repository    = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        clock);

        // The unloadable one is opened first, so it sorts ahead of the healthy one.
        var unloadable = coordinator.resolveOrOpenCurrentGeneration(new LogicalAggregateId<>("Order-broken"));
        var healthy    = coordinator.resolveOrOpenCurrentGeneration(new LogicalAggregateId<>("Order-healthy"));

        var acceptedOrder = new Order(OrderId.random(), CustomerId.random(), 1234);
        acceptedOrder.accept();

        var processor = new DefaultClosingBooksScheduledScanProcessor<>(aggregateType,
                                                                       repository,
                                                                       streamAggregateId -> streamAggregateId.equals(healthy.streamAggregateId())
                                                                               ? java.util.Optional.of(acceptedOrder)
                                                                               : java.util.Optional.empty(),
                                                                       ClosingBooksDecisionPolicies.<String, Order>closeOnlyOnScheduledScan(order -> order.accepted),
                                                                       coordinator,
                                                                       java.util.Optional.empty(),
                                                                       clock,
                                                                       Duration.ofMinutes(5));

        // Batch of one: the first poll only ever sees the unloadable generation.
        assertThat(processor.processNextBatch(1)).describedAs("nothing could be processed in the first poll")
                                                .isZero();
        assertThat(repository.loadOpenGenerations(aggregateType, 10, OffsetDateTime.now(clock)))
                .describedAs("the unloadable generation must be deferred out of the next batch")
                .extracting(AggregateGeneration::streamAggregateId)
                .containsExactly(healthy.streamAggregateId());

        // Second poll now reaches the healthy generation instead of hitting the same wall.
        assertThat(processor.processNextBatch(1)).isEqualTo(1);
        assertThat(repository.resolveCurrentGeneration(aggregateType, new LogicalAggregateId<>("Order-healthy"))).isEmpty();

        // The deferral is a delay, not a write-off: it lapses once the retry delay has passed.
        var afterRetryDelay = OffsetDateTime.now(clock).plusMinutes(6);
        assertThat(repository.loadOpenGenerations(aggregateType, 10, afterRetryDelay))
                .extracting(AggregateGeneration::streamAggregateId)
                .containsExactly(unloadable.streamAggregateId());
    }

    @Test
    void a_generation_whose_policy_throws_is_deferred() {
        var aggregateType      = AggregateType.of("Orders");
        var clock              = Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC);
        var logicalAggregateId = new LogicalAggregateId<>("Order-123");
        var repository         = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        clock);
        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);

        var processor = new DefaultClosingBooksScheduledScanProcessor<String, Order>(aggregateType,
                                                                                     repository,
                                                                                     streamAggregateId -> java.util.Optional.of(new Order(OrderId.random(), CustomerId.random(), 1234)),
                                                                                     context -> {
                                                                                         throw new IllegalStateException("policy blew up");
                                                                                     },
                                                                                     coordinator,
                                                                                     java.util.Optional.empty(),
                                                                                     clock,
                                                                                     Duration.ofMinutes(5));

        assertThat(processor.processNextBatch(10)).isZero();
        assertThat(repository.loadOpenGenerations(aggregateType, 10, OffsetDateTime.now(clock))).isEmpty();
        assertThat(repository.loadOpenGenerations(aggregateType, 10, OffsetDateTime.now(clock).plusMinutes(6))).hasSize(1);
    }
}
