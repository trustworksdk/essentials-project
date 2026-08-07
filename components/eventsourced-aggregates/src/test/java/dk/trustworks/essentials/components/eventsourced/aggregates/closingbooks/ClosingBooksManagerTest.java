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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClosingBooksManagerTest {
    @Test
    void start_polls_processor_and_stop_is_idempotent() {
        var aggregateType = AggregateType.of("Orders");
        var processor = mock(ClosingBooksScheduledScanProcessor.class);
        when(processor.aggregateType()).thenReturn(aggregateType);
        when(processor.processNextBatch(anyInt())).thenReturn(1);
        var fencedLockManager = mock(FencedLockManager.class);
        var fencedLock = mock(FencedLock.class);
        when(fencedLockManager.tryAcquireLock(any(LockName.class), any(Duration.class))).thenReturn(Optional.of(fencedLock));
        var meterRegistry = new SimpleMeterRegistry();
        var manager = new ClosingBooksManager(java.util.List.of(processor),
                                              new ClosingBooksManagerSettings(Duration.ofMillis(10), 25, Duration.ZERO),
                                              fencedLockManager,
                                              LockName.of("closing-books"),
                                              Optional.of(meterRegistry));

        manager.start();
        manager.start();

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> verify(processor, atLeastOnce()).processNextBatch(25));

        manager.stop();
        manager.stop();

        assertThat(manager.isStarted()).isFalse();
        verify(fencedLock, atLeastOnce()).release();
        assertThat(meterRegistry.find(ClosingBooksManagementMeasurementSupport.METRIC_PREFIX + ".manager.poll.outcome")
                                .tag("aggregate_type", aggregateType.toString())
                                .tag("outcome", "processed")
                                .counter())
                .isNotNull();
    }

    @Test
    void manager_skips_processing_when_the_cluster_lock_is_not_acquired() {
        var aggregateType = AggregateType.of("Orders");
        var processor = mock(ClosingBooksScheduledScanProcessor.class);
        when(processor.aggregateType()).thenReturn(aggregateType);
        var fencedLockManager = mock(FencedLockManager.class);
        when(fencedLockManager.tryAcquireLock(any(LockName.class), any(Duration.class))).thenReturn(Optional.empty());

        var meterRegistry = new SimpleMeterRegistry();
        var manager = new ClosingBooksManager(java.util.List.of(processor),
                                              new ClosingBooksManagerSettings(Duration.ofMillis(10), 25, Duration.ZERO),
                                              fencedLockManager,
                                              LockName.of("closing-books"),
                                              Optional.of(meterRegistry));

        manager.start();

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> verify(fencedLockManager, atLeastOnce()).tryAcquireLock(LockName.of("closing-books"), Duration.ZERO));

        manager.stop();

        verify(processor, never()).processNextBatch(anyInt());
        assertThat(meterRegistry.find(ClosingBooksManagementMeasurementSupport.METRIC_PREFIX + ".manager.poll.outcome")
                                .tag("aggregate_type", aggregateType.toString())
                                .tag("outcome", "lock_not_acquired")
                                .counter())
                .isNotNull();
    }

    @Test
    void manager_processes_multiple_aggregate_specific_processors_under_one_lock() {
        var ordersProcessor = mock(ClosingBooksScheduledScanProcessor.class);
        when(ordersProcessor.aggregateType()).thenReturn(AggregateType.of("Orders"));
        when(ordersProcessor.processNextBatch(25)).thenReturn(1);
        var accountsProcessor = mock(ClosingBooksScheduledScanProcessor.class);
        when(accountsProcessor.aggregateType()).thenReturn(AggregateType.of("Accounts"));
        when(accountsProcessor.processNextBatch(25)).thenReturn(0);
        var fencedLockManager = mock(FencedLockManager.class);
        var fencedLock = mock(FencedLock.class);
        when(fencedLockManager.tryAcquireLock(any(LockName.class), any(Duration.class))).thenReturn(Optional.of(fencedLock));

        var manager = new ClosingBooksManager(java.util.List.of(ordersProcessor, accountsProcessor),
                                              new ClosingBooksManagerSettings(Duration.ofMillis(10), 25, Duration.ZERO),
                                              fencedLockManager,
                                              LockName.of("closing-books"));

        manager.start();

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> {
                      verify(ordersProcessor, atLeastOnce()).processNextBatch(25);
                      verify(accountsProcessor, atLeastOnce()).processNextBatch(25);
                  });

        manager.stop();
    }
}
