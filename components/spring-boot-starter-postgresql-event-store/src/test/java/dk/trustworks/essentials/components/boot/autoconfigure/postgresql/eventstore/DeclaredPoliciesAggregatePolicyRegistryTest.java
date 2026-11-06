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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Plain unit tests (no Spring context) of the starter's default policy registries' own contract: the declarations are
 * not read when the registry is created or on {@code register(...)} - which is what keeps the declaration beans out of
 * {@code BeanPostProcessor} registration - and they are read exactly once, before the first read is answered.
 */
class DeclaredPoliciesAggregatePolicyRegistryTest {
    private static final AggregateType LEDGERS = AggregateType.of("Ledgers");

    private static List<EssentialsAggregateDeclarations> declarations() {
        return List.of(EssentialsAggregateDeclarations.builder().declare(LEDGERS, DeclaredLedger.class).build());
    }

    @Test
    void snapshot_registry_reads_the_declarations_once_on_the_first_read_and_not_on_creation_or_register() {
        var calls    = new AtomicInteger();
        var registry = new DeclaredPoliciesAggregateSnapshotPolicyRegistry(counting(calls));

        registry.register(new AggregateSnapshotPolicyDescriptor(OtherLedger.class,
                                                                Optional.empty(),
                                                                OtherLedger.class.getAnnotation(AggregateSnapshotPolicy.class)));
        assertThat(calls).hasValue(0);

        assertThat(registry.findByAggregateImplementationType(DeclaredLedger.class))
                .hasValueSatisfying(descriptor -> {
                    assertThat(descriptor.aggregateType()).contains("Ledgers");
                    assertThat(descriptor.policy().everyNEvents()).isEqualTo(50);
                });
        assertThat(registry.getRegisteredPolicies()).hasSize(2);
        assertThat(registry.findByAggregateImplementationType(OtherLedger.class)).isPresent();
        assertThat(calls).hasValue(1);
    }

    @Test
    void closing_books_registry_reads_the_declarations_once_on_the_first_read_and_not_on_creation_or_register() {
        var calls    = new AtomicInteger();
        var registry = new DeclaredPoliciesAggregateClosingBooksPolicyRegistry(counting(calls));

        registry.register(new AggregateClosingBooksPolicyDescriptor(OtherLedger.class,
                                                                    Optional.empty(),
                                                                    OtherLedger.class.getAnnotation(AggregateClosingBooksPolicy.class)));
        assertThat(calls).hasValue(0);

        assertThat(registry.getRegisteredPolicies()).hasSize(2);
        assertThat(registry.findByAggregateImplementationType(DeclaredLedger.class))
                .hasValueSatisfying(descriptor -> {
                    assertThat(descriptor.aggregateType()).contains("Ledgers");
                    assertThat(descriptor.policy().defaultPolicy()).isEqualTo(ClosingBooksDefaultPolicyType.MANUAL_ONLY);
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void a_supplier_that_throws_is_called_again_on_the_next_read() {
        var calls = new AtomicInteger();
        var registry = new DeclaredPoliciesAggregateSnapshotPolicyRegistry(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("declarations not ready");
            }
            return declarations();
        });

        assertThatThrownBy(() -> registry.findByAggregateImplementationType(DeclaredLedger.class))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.findByAggregateImplementationType(DeclaredLedger.class)).isPresent();
        assertThat(calls).hasValue(2);
    }

    /**
     * A read made from inside the supplier (a declaration bean being created reads the registry) returns without the
     * declared policies instead of recursing; the declared policies are there on the next read.
     */
    @Test
    void a_read_made_while_the_declarations_are_being_read_returns_without_them_instead_of_recursing() {
        var calls               = new AtomicInteger();
        var reentrantReadResult = new AtomicReference<Optional<AggregateClosingBooksPolicyDescriptor>>();
        var registry            = new AtomicReference<DeclaredPoliciesAggregateClosingBooksPolicyRegistry>();
        registry.set(new DeclaredPoliciesAggregateClosingBooksPolicyRegistry(() -> {
            calls.incrementAndGet();
            reentrantReadResult.set(registry.get().findByAggregateImplementationType(DeclaredLedger.class));
            return declarations();
        }));

        assertThat(registry.get().findByAggregateImplementationType(DeclaredLedger.class)).isPresent();
        assertThat(reentrantReadResult.get()).isEmpty();
        assertThat(calls).hasValue(1);
    }

    /**
     * A second thread's first read, made while another thread is still registering the declared policies, waits for
     * that registration instead of answering from a registry without them.
     */
    @Test
    void a_concurrent_first_read_waits_until_the_declared_policies_are_registered() throws Exception {
        var supplierEntered = new CountDownLatch(1);
        var releaseSupplier = new CountDownLatch(1);
        var registry = new DeclaredPoliciesAggregateSnapshotPolicyRegistry(() -> {
            supplierEntered.countDown();
            try {
                releaseSupplier.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return declarations();
        });

        // Inner finally releases the supplier before close() waits for the pool's threads to finish
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> registry.findByAggregateImplementationType(DeclaredLedger.class));
                assertThat(supplierEntered.await(5, TimeUnit.SECONDS)).isTrue();

                var secondThread = new AtomicReference<Thread>();
                var second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return registry.findByAggregateImplementationType(DeclaredLedger.class);
                });

                // The second reader must block on the registry rather than answer early
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!(secondThread.get() != null && secondThread.get().getState() == Thread.State.BLOCKED)) {
                    if (second.isDone()) {
                        fail("The concurrent first read answered before the declared policies were registered: " + second.get());
                    }
                    if (System.nanoTime() > deadline) {
                        fail("The concurrent first read neither blocked nor answered");
                    }
                    Thread.onSpinWait();
                }

                releaseSupplier.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
                assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
            } finally {
                releaseSupplier.countDown();
                executor.shutdownNow();
            }
        }
    }

    private static Supplier<List<EssentialsAggregateDeclarations>> counting(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return declarations();
        };
    }

    @AggregateSnapshotPolicy(everyNEvents = 50)
    @AggregateClosingBooksPolicy(defaultPolicy = ClosingBooksDefaultPolicyType.MANUAL_ONLY)
    static class DeclaredLedger {
    }

    @AggregateSnapshotPolicy(everyNEvents = 20)
    @AggregateClosingBooksPolicy(defaultPolicy = ClosingBooksDefaultPolicyType.MANUAL_ONLY)
    static class OtherLedger {
    }
}
