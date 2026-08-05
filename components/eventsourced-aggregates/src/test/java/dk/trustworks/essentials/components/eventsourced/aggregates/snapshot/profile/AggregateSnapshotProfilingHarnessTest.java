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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.profile;

import ch.qos.logback.classic.Level;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.Event;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateSnapshotProfilingHarnessTest {
    private static final Logger log = LoggerFactory.getLogger(AggregateSnapshotProfilingHarnessTest.class);

    @BeforeAll
    static void reduceProfilingTestLogNoise() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dk.trustworks.essentials.components.eventsourced.aggregates.modern")).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state")).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dk.trustworks.essentials.components.eventsourced.aggregates.classic")).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern")).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic")).setLevel(Level.INFO);
    }

    @Test
    void can_profile_rehydration_and_snapshot_candidates_for_a_given_aggregate() {
        var harness = new AggregateSnapshotProfilingHarness();
        var settings = new AggregateSnapshotProfilingSettings(List.of(100, 500, 1000),
                                                              List.of(25, 50, 100, 250),
                                                              1,
                                                              3,
                                                              Duration.ofNanos(50_000));

        var reports = List.of(harness.profile(new ModernOrderProfilingAdapter(), settings),
                              harness.profile(new ModernOrderWithStateProfilingAdapter(), settings),
                              harness.profile(new ClassicOrderProfilingAdapter(), settings));

        assertThat(reports).extracting(AggregateSnapshotProfileReport::aggregateName)
                           .containsExactly("modern-order", "modern-order-with-state", "classic-order");
        assertThat(reports).allSatisfy(report -> {
            assertThat(report.replayMeasurements()).hasSize(3);
            assertThat(report.snapshotMeasurements()).isNotEmpty();
            assertThat(report.replayMeasurements()).allSatisfy(measurement -> {
                assertThat(measurement.averageReplayTime()).isGreaterThanOrEqualTo(Duration.ZERO);
                assertThat(measurement.fastestReplayTime()).isGreaterThanOrEqualTo(Duration.ZERO);
                assertThat(measurement.slowestReplayTime()).isGreaterThanOrEqualTo(Duration.ZERO);
            });
            assertThat(report.snapshotMeasurements()).allSatisfy(measurement -> {
                assertThat(measurement.averageSnapshotCreationTime()).isGreaterThanOrEqualTo(Duration.ZERO);
                assertThat(measurement.averageReplayFromSnapshotTime()).isGreaterThanOrEqualTo(Duration.ZERO);
                assertThat(measurement.replayedTailEventCount()).isGreaterThanOrEqualTo(0);
            });
        });
    }

    @Test
    void can_render_a_text_report_for_a_test_aggregate_profile() {
        var harness = new AggregateSnapshotProfilingHarness();
        var report = harness.profile(new ModernOrderProfilingAdapter(),
                                     new AggregateSnapshotProfilingSettings(List.of(100, 300),
                                                                           List.of(25, 50, 100),
                                                                           0,
                                                                           2,
                                                                           Duration.ofNanos(25_000)));
        var rendered = new AggregateSnapshotProfileReportTextRenderer().render(report);
        logRenderedReportIfEnabled(rendered);

        assertThat(rendered).contains("Aggregate Snapshot Profile");
        assertThat(rendered).contains("Aggregate: modern-order");
        assertThat(rendered).contains("Replay Measurements");
        assertThat(rendered).contains("Snapshot Measurements");
        assertThat(rendered).contains("Recommendation");
        assertThat(rendered).containsPattern("\\d+\\.\\d{2}(us|ms|s)|\\d+ns");
    }

    private void logRenderedReportIfEnabled(String renderedReport) {
        if (Boolean.getBoolean("essentials.snapshot.profile.print-report")) {
            log.info("\n{}", renderedReport);
        }
    }

    private static final class ModernOrderProfilingAdapter implements AggregateSnapshotProfilingAdapter<Order, OrderEvent, Order> {
        @Override
        public String aggregateName() {
            return "modern-order";
        }

        @Override
        public List<OrderEvent> createEventHistory(int eventCount) {
            var orderId = OrderId.random();
            var aggregate = new Order(orderId, CustomerId.random(), 1234);
            var products = new ArrayList<ProductId>();

            while (aggregate.getUncommittedChanges().events.size() < eventCount) {
                int step = aggregate.getUncommittedChanges().events.size();
                if (products.isEmpty() || step % 3 == 0) {
                    var productId = ProductId.random();
                    products.add(productId);
                    aggregate.addProduct(productId, (step % 5) + 1);
                } else {
                    var productId = products.get(step % products.size());
                    aggregate.adjustProductQuantity(productId, ((step + 1) % 7) + 1);
                }
            }

            return List.copyOf(aggregate.getUncommittedChanges().events);
        }

        @Override
        public Order rehydrateFromEvents(List<OrderEvent> eventHistory) {
            var orderId = eventHistory.isEmpty() ? OrderId.random() : eventHistory.get(0).orderId;
            return new Order(orderId).rehydrate(eventHistory.stream());
        }

        @Override
        public Order createSnapshot(Order aggregate) {
            var snapshot = new Order(aggregate.aggregateId());
            snapshot.accepted = aggregate.accepted;
            snapshot.productAndQuantity = new HashMap<>(aggregate.productAndQuantity);
            return snapshot;
        }

        @Override
        public Order rehydrateFromSnapshot(Order snapshot, List<OrderEvent> remainingEvents) {
            var aggregate = createSnapshot(snapshot);
            if (!remainingEvents.isEmpty()) {
                aggregate.rehydrate(remainingEvents.stream());
            }
            return aggregate;
        }
    }

    private static final class ModernOrderWithStateProfilingAdapter implements AggregateSnapshotProfilingAdapter<dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order, OrderEvent, dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order> {
        @Override
        public String aggregateName() {
            return "modern-order-with-state";
        }

        @Override
        public List<OrderEvent> createEventHistory(int eventCount) {
            var orderId = OrderId.random();
            var aggregate = new dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order(orderId, CustomerId.random(), 1234);
            var products = new ArrayList<ProductId>();

            while (aggregate.getUncommittedChanges().events.size() < eventCount) {
                int step = aggregate.getUncommittedChanges().events.size();
                if (products.isEmpty() || step % 3 == 0) {
                    var productId = ProductId.random();
                    products.add(productId);
                    aggregate.addProduct(productId, (step % 5) + 1);
                } else {
                    var productId = products.get(step % products.size());
                    aggregate.adjustProductQuantity(productId, ((step + 1) % 7) + 1);
                }
            }

            return List.copyOf(aggregate.getUncommittedChanges().events);
        }

        @Override
        public dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order rehydrateFromEvents(List<OrderEvent> eventHistory) {
            var orderId = eventHistory.isEmpty() ? OrderId.random() : eventHistory.get(0).orderId;
            return new dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order(orderId).rehydrate(eventHistory.stream());
        }

        @Override
        public dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order createSnapshot(dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order aggregate) {
            var snapshot = new dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order(aggregate.aggregateId());
            snapshot.state().accepted = aggregate.state().accepted;
            snapshot.state().productAndQuantity = new HashMap<>(aggregate.state().productAndQuantity);
            return snapshot;
        }

        @Override
        public dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order rehydrateFromSnapshot(dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order snapshot,
                                                                                                                          List<OrderEvent> remainingEvents) {
            var aggregate = createSnapshot(snapshot);
            if (!remainingEvents.isEmpty()) {
                aggregate.rehydrate(remainingEvents.stream());
            }
            return aggregate;
        }
    }

    private static final class ClassicOrderProfilingAdapter implements AggregateSnapshotProfilingAdapter<dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order, Event<OrderId>, dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order> {
        @Override
        public String aggregateName() {
            return "classic-order";
        }

        @Override
        public List<Event<OrderId>> createEventHistory(int eventCount) {
            var aggregate = new dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order(OrderId.random(), CustomerId.random(), 1234);
            var products = new ArrayList<ProductId>();

            while (aggregate.getUncommittedChanges().events.size() < eventCount) {
                int step = aggregate.getUncommittedChanges().events.size();
                if (products.isEmpty() || step % 3 == 0) {
                    var productId = ProductId.random();
                    products.add(productId);
                    aggregate.addProduct(productId, (step % 5) + 1);
                } else {
                    var productId = products.get(step % products.size());
                    aggregate.adjustProductQuantity(productId, ((step + 1) % 7) + 1);
                }
            }

            return List.copyOf(aggregate.getUncommittedChanges().events);
        }

        @Override
        public dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order rehydrateFromEvents(List<Event<OrderId>> eventHistory) {
            return new dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order().rehydrate(eventHistory.stream());
        }

        @Override
        public dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order createSnapshot(dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order aggregate) {
            var snapshot = new dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order();
            copyClassicAggregateState(aggregate, snapshot);
            return snapshot;
        }

        @Override
        public dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order rehydrateFromSnapshot(dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order snapshot,
                                                                                                                List<Event<OrderId>> remainingEvents) {
            var aggregate = createSnapshot(snapshot);
            if (!remainingEvents.isEmpty()) {
                aggregate.rehydrate(remainingEvents.stream());
            }
            return aggregate;
        }

        @SuppressWarnings("unchecked")
        private void copyClassicAggregateState(dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order source,
                                               dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order target) {
            try {
                setField(target, dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order.class, "productAndQuantity", new HashMap<>((Map<ProductId, Integer>) getField(source, dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order.class, "productAndQuantity")));
                setField(target, dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order.class, "accepted", getField(source, dk.trustworks.essentials.components.eventsourced.aggregates.classic.Order.class, "accepted"));
                setField(target, dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot.class, "aggregateId", source.aggregateId());
                setField(target, dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot.class, "eventOrderOfLastAppliedEvent", source.eventOrderOfLastAppliedEvent());
                setField(target, dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot.class, "eventOrderOfLastRehydratedEvent", source.eventOrderOfLastAppliedEvent());
                setField(target, dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot.class, "hasBeenRehydrated", true);
                target.markChangesAsCommitted();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to copy classic aggregate snapshot state", e);
            }
        }

        private Object getField(Object target, Class<?> declaringType, String fieldName) throws ReflectiveOperationException {
            var field = findField(declaringType, fieldName);
            return field.get(target);
        }

        private void setField(Object target, Class<?> declaringType, String fieldName, Object value) throws ReflectiveOperationException {
            var field = findField(declaringType, fieldName);
            field.set(target, value);
        }

        private Field findField(Class<?> declaringType, String fieldName) throws NoSuchFieldException {
            var field = declaringType.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        }
    }
}
