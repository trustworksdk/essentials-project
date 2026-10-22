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

package dk.trustworks.essentials.components.queue.shardowned;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A handler that returns around the charge before a release ({@code ShardOwner.chargeInHandlerBeforeRelease},
 * {@code OrderedShardOwner.chargeKeysInHandlerBeforeRelease}) ends with its message either acknowledged or dead-lettered by
 * the charge, never both. A handler that returned before the charge took its candidates is not one of them: the charge
 * leaves its message to the acknowledgement it queued. One that returns once the charge has its message waits for that
 * charge and is told of the dead letter, and queues no acknowledgement.
 * <p>
 * Both owners used to take the candidates before the charge started: a handler returning in between found no charge
 * running and no record, queued its acknowledgement and was counted delivered, and the charge then charged the row - still
 * in the table, the acknowledgement not flushed - and dead-lettered it, so resurrecting that dead letter delivered a
 * handled message again.
 * <p>
 * And a handler returning mid-charge, its message charged below the limit, finishes with the charge on both lanes: it asks
 * the ledger before it takes its owner's {@code stateLock}, which the charge takes under the ledger's monitor
 * ({@code ReleaseChargeLedger}'s <b>Locking</b>).
 */
class ReleaseChargeHandlerReturnTest {

    private static final int  MAX_ATTEMPTS = 1;
    private static final long SEQ          = 7L;

    @Test
    void an_ordered_handler_returning_after_a_charge_is_asked_for_and_before_it_starts_is_acknowledged_and_not_dead_lettered() throws Exception {
        var handler  = new HeldHandler();
        var owner    = orderedOwner(handler);
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(SEQ, MAX_ATTEMPTS));
        dispatchOrdered(owner);

        var charge = chargeWhileTheLedgerIsHeld(field(owner, OrderedShardOwner.class, "releaseChargeLedger"), handler, owner,
                                                () -> owner.chargeKeysInHandlerBeforeRelease(database.connection(),
                                                                                             OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER)
                                                           .charged());

        assertThat(pendingAcks(owner, OrderedShardOwner.class)).as("the handler queued its acknowledgement").contains(SEQ);
        assertThat(metrics(owner, OrderedShardOwner.class).delivered.sum()).isEqualTo(1);
        assertThat(database.moved).as("a message whose handler returned before the charge took its candidates is not dead-lettered").isEmpty();
        assertThat(charge).as("nor charged").isZero();
        assertThat(database.chargesAsked).as("no charge statement was issued").isEmpty();
    }

    @Test
    void an_ordered_handler_returning_while_the_charge_holds_its_message_waits_and_is_not_acknowledged() throws Exception {
        var handler  = new HeldHandler();
        var owner    = orderedOwner(handler);
        var waited   = new AtomicReference<Boolean>();
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(SEQ, MAX_ATTEMPTS))
                .onCharge(() -> waited.set(handlerWaits(handler, owner)));
        dispatchOrdered(owner);

        var charge = owner.chargeKeysInHandlerBeforeRelease(database.connection(), OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER);
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.inFlight() == 0);

        assertThat(waited.get()).as("the handler, its message in the running charge, waits for that charge").isTrue();
        assertThat(charge.deadLetteredKeys()).as("premise: the charge dead-lettered the message").containsExactly("K");
        assertThat(database.moved).containsExactly(SEQ);
        assertThat(pendingAcks(owner, OrderedShardOwner.class)).as("a message the charge dead-lettered is not acknowledged").doesNotContain(SEQ);
        assertThat(metrics(owner, OrderedShardOwner.class).delivered.sum()).isZero();
    }

    @Test
    void an_unordered_handler_returning_after_a_charge_is_asked_for_and_before_it_starts_is_acknowledged_and_not_dead_lettered() throws Exception {
        var handler  = new HeldHandler();
        var owner    = unorderedOwner(handler);
        var database = new ChargingConnection(ShardOwnedSchema.UNORDERED_TABLE, Map.of(SEQ, MAX_ATTEMPTS));
        deliverUnordered(owner);

        var charge = chargeWhileTheLedgerIsHeld(field(owner, ShardOwner.class, "releaseChargeLedger"), handler, owner,
                                                () -> owner.chargeInHandlerBeforeRelease(database.connection()).charged());

        assertThat(pendingAcks(owner, ShardOwner.class)).as("the handler queued its acknowledgement").contains(SEQ);
        assertThat(metrics(owner, ShardOwner.class).delivered.sum()).isEqualTo(1);
        assertThat(database.moved).as("a message whose handler returned before the charge took its candidates is not dead-lettered").isEmpty();
        assertThat(charge).as("nor charged").isZero();
        assertThat(database.chargesAsked).as("no charge statement was issued").isEmpty();
    }

    @Test
    void an_unordered_handler_returning_while_the_charge_holds_its_message_waits_and_is_not_acknowledged() throws Exception {
        var handler  = new HeldHandler();
        var owner    = unorderedOwner(handler);
        var waited   = new AtomicReference<Boolean>();
        var database = new ChargingConnection(ShardOwnedSchema.UNORDERED_TABLE, Map.of(SEQ, MAX_ATTEMPTS))
                .onCharge(() -> waited.set(handlerWaits(handler, owner)));
        deliverUnordered(owner);

        var charge = owner.chargeInHandlerBeforeRelease(database.connection());
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.inFlight() == 0);

        assertThat(waited.get()).as("the handler, its message in the running charge, waits for that charge").isTrue();
        assertThat(charge.deadLetteredSeqs()).as("premise: the charge dead-lettered the message").containsExactly(SEQ);
        assertThat(database.moved).containsExactly(SEQ);
        assertThat(pendingAcks(owner, ShardOwner.class)).as("a message the charge dead-lettered is not acknowledged").doesNotContain(SEQ);
        assertThat(metrics(owner, ShardOwner.class).delivered.sum()).isZero();
    }

    @Test
    void an_ordered_handler_recording_its_failure_is_not_taken_by_a_charge_that_starts_meanwhile() throws Exception {
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(SEQ, MAX_ATTEMPTS));
        var charge   = new CompletableFuture<Integer>();
        var owner    = new AtomicReference<OrderedShardOwner>();
        owner.set(orderedOwner(() -> {
            throw new IllegalStateException("handler failed");
        }, refusingConnectionsAfter(() -> chargeOnAnotherThread(charge, () -> owner.get().chargeKeysInHandlerBeforeRelease(
                database.connection(), OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER).charged()))));

        dispatchOrdered(owner.get());
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.get().inFlight() == 0);

        assertThat(charge.get(10, TimeUnit.SECONDS)).as("the message whose handler threw is not charged while its failure is recorded").isZero();
        assertThat(database.moved).as("nor dead-lettered").isEmpty();
    }

    @Test
    void an_unordered_handler_recording_its_failure_is_not_taken_by_a_charge_that_starts_meanwhile() throws Exception {
        var database = new ChargingConnection(ShardOwnedSchema.UNORDERED_TABLE, Map.of(SEQ, MAX_ATTEMPTS));
        var charge   = new CompletableFuture<Integer>();
        var owner    = new AtomicReference<ShardOwner>();
        owner.set(unorderedOwner(() -> {
            throw new IllegalStateException("handler failed");
        }, refusingConnectionsAfter(() -> chargeOnAnotherThread(charge, () -> owner.get().chargeInHandlerBeforeRelease(database.connection()).charged()))));

        deliverUnordered(owner.get());
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.get().inFlight() == 0);

        assertThat(charge.get(10, TimeUnit.SECONDS)).as("the message whose handler threw is not charged while its failure is recorded").isZero();
        assertThat(database.moved).as("nor dead-lettered").isEmpty();
    }

    /**
     * The ledger's monitor is taken before the owner's {@code stateLock}: a charge holds the monitor across its statements and
     * takes {@code stateLock} in {@code onCharged}. A handler of the same owner returning while that charge runs, its message
     * charged below the limit, waits for the charge on the monitor and must hold no {@code stateLock} while it does - asked
     * from inside {@code synchronized (stateLock)}, handler and charge would each wait for the other's lock for good. Once the
     * charge ends, the handler's message is acknowledged as usual.
     */
    @Test
    void an_ordered_handler_returning_while_a_charge_holds_the_ledger_finishes_with_it_and_is_acknowledged() throws Exception {
        var handler  = new HeldHandler();
        var owner    = orderedOwner(handler::handle, noDatabase(), 3);
        var waited   = new AtomicReference<Boolean>();
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(SEQ, 1))
                .onCharge(() -> waited.set(handlerBlocksOnTheCharge(handler)));
        dispatchOrdered(owner);

        var charge = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var answer = owner.chargeKeysInHandlerBeforeRelease(database.connection(), OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER);
            await().atMost(Duration.ofSeconds(5)).until(() -> owner.inFlight() == 0);
            return answer;
        }, "the charge and the handler returning during it both finish");

        assertThat(waited.get()).as("premise: the handler returned during the charge and waited for it").isTrue();
        assertThat(charge.charged()).as("premise: the charge charged the message").isEqualTo(1);
        assertThat(charge.deadLetteredKeys()).as("below the limit: not dead-lettered").isEmpty();
        assertThat(database.moved).isEmpty();
        assertThat(pendingAcks(owner, OrderedShardOwner.class)).as("the handler's message is acknowledged").contains(SEQ);
        assertThat(metrics(owner, OrderedShardOwner.class).delivered.sum()).isEqualTo(1);
    }

    /**
     * The unordered twin, through the failure path: a handler that throws while a charge holds the ledger asks the ledger on
     * its way to recording the failure, waits for the charge, which takes {@code stateLock} in {@code onCharged}, and then
     * goes on to record the failure. Asked from inside {@code synchronized (stateLock)}, it would deadlock with that charge.
     */
    @Test
    void an_unordered_handler_failing_while_a_charge_holds_the_ledger_finishes_with_it_and_records_its_failure() throws Exception {
        var handler         = new HeldHandler(true);
        var failureRecorded = new CountDownLatch(1);
        var owner           = unorderedOwner(handler::handle, refusingConnectionsAfter(failureRecorded::countDown), 3);
        var waited          = new AtomicReference<Boolean>();
        var database = new ChargingConnection(ShardOwnedSchema.UNORDERED_TABLE, Map.of(SEQ, 1))
                .onCharge(() -> waited.set(handlerBlocksOnTheCharge(handler)));
        deliverUnordered(owner);

        var charge = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var answer = owner.chargeInHandlerBeforeRelease(database.connection());
            await().atMost(Duration.ofSeconds(5)).until(() -> owner.inFlight() == 0);
            return answer;
        }, "the charge and the handler failing during it both finish");

        assertThat(waited.get()).as("premise: the handler threw during the charge and waited for it").isTrue();
        assertThat(charge.charged()).as("premise: the charge charged the message").isEqualTo(1);
        assertThat(charge.deadLetteredSeqs()).as("below the limit: not dead-lettered").isEmpty();
        assertThat(database.moved).isEmpty();
        assertThat(failureRecorded.getCount()).as("the handler went on to record its failure").isZero();
        assertThat(metrics(owner, ShardOwner.class).handlerFailures.sum()).isEqualTo(1);
    }

    /**
     * Run {@code charge} on a thread of its own and wait for it: called from the handler thread while it records the
     * handler's failure, its message still in its handler.
     */
    private static void chargeOnAnotherThread(CompletableFuture<Integer> answer, Callable<Integer> charge) {
        Thread.ofPlatform().daemon().start(() -> {
            try {
                answer.complete(charge.call());
            } catch (Exception | Error e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            answer.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Reported by the test, which asks the answer itself
        }
    }

    /**
     * Start {@code charge} on a thread of its own while this thread holds the ledger's monitor, so it stops where it enters
     * the ledger; let the handler return and leave its handler in that window; then let the charge go on.
     *
     * @return what {@code charge} answered
     */
    private static int chargeWhileTheLedgerIsHeld(Object ledger, HeldHandler handler, LeasedOwner owner, Callable<Integer> charge) throws Exception {
        var monitor  = field(ledger, ReleaseChargeLedger.class, "monitor");
        var answer   = new CompletableFuture<Integer>();
        Thread charging;
        synchronized (monitor) {
            charging = Thread.ofPlatform().daemon().start(() -> {
                try {
                    answer.complete(charge.call());
                } catch (Exception | Error e) {
                    answer.completeExceptionally(e);
                }
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> charging.getState() == Thread.State.BLOCKED);
            handler.mayReturn.countDown();
            // Premise: the handler returns and leaves its handler while the charge waits to enter the ledger
            await().atMost(Duration.ofSeconds(10)).until(() -> owner.inFlight() == 0);
        }
        return answer.get(10, TimeUnit.SECONDS);
    }

    /**
     * Let the handler return, from inside the running charge, and tell whether it is still in its handler 300 ms after it
     * returned: waiting for the charge.
     */
    private static boolean handlerWaits(HeldHandler handler, LeasedOwner owner) {
        handler.mayReturn.countDown();
        try {
            if (!handler.returned.await(10, TimeUnit.SECONDS)) {
                return false;
            }
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return owner.inFlight() == 1;
    }

    /**
     * Let the handler return, from inside the running charge - which holds the ledger's monitor - and wait until its thread
     * is blocked on a monitor: the handler is waiting for the charge.
     *
     * @return whether it was seen blocked within 5 s
     */
    private static boolean handlerBlocksOnTheCharge(HeldHandler handler) {
        handler.mayReturn.countDown();
        try {
            if (!handler.returned.await(5, TimeUnit.SECONDS)) {
                return false;
            }
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (handler.thread.getState() == Thread.State.BLOCKED) {
                    return true;
                }
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * A handler that returns once {@link #mayReturn} counts down - or throws then, when built {@code failing} - and counts
     * {@link #returned} down as it returns. {@link #thread} is the thread it runs on.
     */
    private static final class HeldHandler {
        final    CountDownLatch mayReturn = new CountDownLatch(1);
        final    CountDownLatch returned  = new CountDownLatch(1);
        final    boolean        failing;
        volatile Thread         thread;

        HeldHandler() {
            this(false);
        }

        HeldHandler(boolean failing) {
            this.failing = failing;
        }

        void handle() {
            thread = Thread.currentThread();
            try {
                if (!mayReturn.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not let return");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            returned.countDown();
            if (failing) {
                throw new IllegalStateException("handler failed");
            }
        }
    }

    private static OrderedShardOwner orderedOwner(HeldHandler handler) {
        return orderedOwner(handler::handle, noDatabase());
    }

    private static OrderedShardOwner orderedOwner(Runnable handler, DataSource dataSource) {
        return orderedOwner(handler, dataSource, MAX_ATTEMPTS);
    }

    private static OrderedShardOwner orderedOwner(Runnable handler, DataSource dataSource, int maxAttempts) {
        return new OrderedShardOwner(new ShardOwnedStorage(dataSource, (short) 1), 3, 1L, ShardOwnerSettings.builder().build(),
                                     (id, key, payload, type) -> handler.run(), new ShardOwnerMetrics(),
                                     RedeliveryPolicy.fixed(Duration.ofSeconds(1), maxAttempts), "instance-under-test", new ShardWakeup(),
                                     handlerThreads(), false);
    }

    private static ShardOwner unorderedOwner(HeldHandler handler) {
        return unorderedOwner(handler::handle, noDatabase());
    }

    private static ShardOwner unorderedOwner(Runnable handler, DataSource dataSource) {
        return unorderedOwner(handler, dataSource, MAX_ATTEMPTS);
    }

    private static ShardOwner unorderedOwner(Runnable handler, DataSource dataSource, int maxAttempts) {
        // A bound of a nanosecond: the message in its handler is past it by the time the charge asks
        var settings = ShardOwnerSettings.builder().build().withStuckHandlerTimeout(Duration.ofNanos(1));
        return new ShardOwner(new ShardOwnedStorage(dataSource, (short) 1), 3, 1L, settings, (id, payload, type) -> handler.run(),
                              new ShardOwnerMetrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), maxAttempts), new ShardWakeup(),
                              "instance-under-test", handlerThreads(), false);
    }

    private static HandlerDispatch handlerThreads() {
        return new HandlerDispatch(command -> Thread.ofPlatform().daemon().start(command), 8);
    }

    // DataSource's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static DataSource refusingConnectionsAfter(Runnable action) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> {
                                                       if ("getConnection".equals(method.getName())) {
                                                           action.run();
                                                           throw new SQLException("no database here");
                                                       }
                                                       throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    // DataSource's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static DataSource noDatabase() {
        // Nothing here reaches the DataSource: every write goes through the connection each test passes.
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> {
                                                       throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    /**
     * Hold message {@link #SEQ} of key {@code K} and dispatch it, as a read and the pump do. Set directly: reaching it
     * through a read would need a database.
     */
    @SuppressWarnings("unchecked")
    private static void dispatchOrdered(OrderedShardOwner owner) throws Exception {
        synchronized (field(owner, OrderedShardOwner.class, "stateLock")) {
            ((Map<String, NavigableMap<Long, ShardOwnedStorage.OrderedRow>>) field(owner, OrderedShardOwner.class, "readyByKey"))
                    .computeIfAbsent("K", key -> new TreeMap<>()).put(5L, new ShardOwnedStorage.OrderedRow(SEQ, "K", 5L, new byte[0], 0));
        }
        var dispatch = OrderedShardOwner.class.getDeclaredMethod("dispatchReadyKeys");
        dispatch.setAccessible(true);
        assertThat((int) dispatch.invoke(owner)).as("premise: the message is in its handler").isEqualTo(1);
    }

    /**
     * Deliver message {@link #SEQ}, as the cursor read does once it has taken a permit.
     */
    private static void deliverUnordered(ShardOwner owner) throws Exception {
        var deliver = ShardOwner.class.getDeclaredMethod("deliver", ShardOwnedStorage.Row.class);
        deliver.setAccessible(true);
        assertThat((boolean) deliver.invoke(owner, new ShardOwnedStorage.Row(SEQ, new byte[0], 0))).as("premise: the message is in its handler").isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> pendingAcks(Object owner, Class<?> type) throws ReflectiveOperationException {
        synchronized (field(owner, type, "stateLock")) {
            return Set.copyOf((Set<Long>) field(owner, type, "pendingAcks"));
        }
    }

    private static ShardOwnerMetrics metrics(Object owner, Class<?> type) throws ReflectiveOperationException {
        return (ShardOwnerMetrics) field(owner, type, "metrics");
    }

    private static Object field(Object target, Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
