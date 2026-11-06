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
import java.lang.reflect.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pump's map from a unit's wake-up to the owners serving it ({@code ShardPump.byWakeup}). When a unit is re-acquired,
 * its new owner can be added on the queue's thread while the pump thread drops the old one; the new owner must stay
 * mapped, or a wake-up signalled for the unit never names it and {@link ShardPump#servesActively} answers false for its
 * whole tenure.
 */
class ShardPumpWakeupMapTest {
    private static final long WAIT_SECONDS = 10;

    /**
     * Forces the interleaving the drop used to lose the new owner to: the old owner is removed from the wake-up's set,
     * the new owner is added to that same set, and only then is the emptied set unmapped. The old owner's
     * {@code wakeup()} holds the drop at the point it asks for its wake-up a second time, which the old
     * {@code forgetWakeup} did just before unmapping; a drop that asks only once runs straight through.
     */
    @Test
    void a_new_owner_added_while_the_old_owner_of_the_same_unit_is_dropped_stays_mapped_to_its_wake_up() throws Exception {
        var pump          = pump();
        var unitWakeup    = new ShardWakeup(pump.wakeup());
        var armed         = new AtomicBoolean();
        var callsArmed    = new AtomicInteger();
        var dropProceeded = new CountDownLatch(1);
        var newOwnerAdded = new CountDownLatch(1);
        var oldOwner = owner(unitWakeup, () -> {
            if (armed.get() && callsArmed.incrementAndGet() == 2) {
                dropProceeded.countDown();
                newOwnerAdded.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        });
        var newOwner = owner(unitWakeup, () -> {
        });
        pump.add(oldOwner);
        armed.set(true);

        var drop    = ShardPump.class.getDeclaredMethod("drop", LeasedOwner.class);
        drop.setAccessible(true);
        var dropped = new CompletableFuture<Void>();
        var dropper = Thread.ofPlatform().name("pump-drop-under-test").start(() -> {
            try {
                drop.invoke(pump, oldOwner);
                dropped.complete(null);
            } catch (Throwable e) {
                dropped.completeExceptionally(e);
            } finally {
                dropProceeded.countDown();
            }
        });
        assertThat(dropProceeded.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the drop reached its unmapping or finished").isTrue();
        pump.add(newOwner);
        newOwnerAdded.countDown();
        assertThat(dropper.join(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        dropped.get();

        assertThat(pump.serves(oldOwner)).as("premise: the old owner was dropped").isFalse();
        assertThat(pump.servesActively(unitWakeup)).as("the new owner is still mapped to the unit's wake-up").isTrue();

        drop.invoke(pump, newOwner);
        assertThat(pump.servesActively(unitWakeup)).as("dropping the last owner unmaps the wake-up").isFalse();
    }

    /**
     * Forces the other half of the interleaving: {@code add} has found the wake-up's set, still holding the old owner,
     * but has not put the new owner in it yet when the drop empties and unmaps that set. The new owner's
     * {@code hashCode()}, which the set asks for when the owner is put in it (the pump's owner list and its added-queue
     * never ask), holds {@code add} there; the drop is then started and let run until it has finished or is blocked,
     * and only then is {@code add} let go. An {@code add} that puts the owner in the set atomically with the map entry
     * holds that entry's lock meanwhile, so the drop waits for it.
     */
    @Test
    void a_drop_of_the_old_owner_while_the_new_owner_is_being_put_in_the_set_leaves_the_new_owner_mapped() throws Exception {
        var pump          = pump();
        var unitWakeup    = new ShardWakeup(pump.wakeup());
        var holdInsertion = new AtomicBoolean(true);
        var insertion     = new CountDownLatch(1);
        var letAddGo      = new CountDownLatch(1);
        var oldOwner      = owner(unitWakeup, () -> {
        });
        var newOwner = owner(unitWakeup, () -> {
        }, () -> {
            if (holdInsertion.compareAndSet(true, false)) {
                insertion.countDown();
                letAddGo.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        });
        pump.add(oldOwner);
        var drop = ShardPump.class.getDeclaredMethod("drop", LeasedOwner.class);
        drop.setAccessible(true);
        // Once on an owner of another unit, so the drop under test does not block on loading or linking anything
        drop.invoke(pump, owner(new ShardWakeup(pump.wakeup()), () -> {
        }));

        var added = new CompletableFuture<Void>();
        var adder = Thread.ofPlatform().name("queue-add-under-test").start(() -> {
            try {
                pump.add(newOwner);
                added.complete(null);
            } catch (Throwable e) {
                added.completeExceptionally(e);
            } finally {
                insertion.countDown();
            }
        });
        assertThat(insertion.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the add reached the insertion into the set").isTrue();
        var dropped = new CompletableFuture<Void>();
        var dropper = Thread.ofPlatform().name("pump-drop-under-test").start(() -> {
            try {
                drop.invoke(pump, oldOwner);
                dropped.complete(null);
            } catch (Throwable e) {
                dropped.completeExceptionally(e);
            }
        });
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (dropper.getState() != Thread.State.BLOCKED && dropper.getState() != Thread.State.TERMINATED && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
        assertThat(dropper.getState()).as("the drop finished or is blocked").isIn(Thread.State.BLOCKED, Thread.State.TERMINATED);
        letAddGo.countDown();
        assertThat(adder.join(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        assertThat(dropper.join(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        added.get();
        dropped.get();

        assertThat(pump.serves(oldOwner)).as("premise: the old owner was dropped").isFalse();
        assertThat(pump.servesActively(unitWakeup)).as("the new owner is still mapped to the unit's wake-up").isTrue();
    }

    @FunctionalInterface
    private interface OnWakeup {
        void called() throws InterruptedException;
    }

    @FunctionalInterface
    private interface OnHashCode {
        void called() throws InterruptedException;
    }

    private static LeasedOwner owner(ShardWakeup wakeup, OnWakeup onWakeup) {
        return owner(wakeup, onWakeup, () -> {
        });
    }

    /**
     * An owner holding its lease, free to deliver and not handing over, whose {@code wakeup()} runs {@code onWakeup}
     * first and whose {@code hashCode()} runs {@code onHashCode} first. Nothing else is asked of it here. Its
     * {@code equals} is identity, as the pump's set needs: comparing with {@code equals()} on the proxy would call back
     * into this handler and recurse, hence the suppression.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static LeasedOwner owner(ShardWakeup wakeup, OnWakeup onWakeup, OnHashCode onHashCode) {
        return (LeasedOwner) Proxy.newProxyInstance(ShardPumpWakeupMapTest.class.getClassLoader(), new Class<?>[]{LeasedOwner.class},
                                                    (proxy, method, args) -> switch (method.getName()) {
                                                        case "wakeup" -> {
                                                            onWakeup.called();
                                                            yield wakeup;
                                                        }
                                                        case "leaseHeld", "deliveryPermitted" -> true;
                                                        case "handingOver" -> false;
                                                        case "hashCode" -> {
                                                            onHashCode.called();
                                                            yield System.identityHashCode(proxy);
                                                        }
                                                        case "equals" -> proxy == args[0];
                                                        case "toString" -> "owner@" + Integer.toHexString(System.identityHashCode(proxy));
                                                        default -> throw new UnsupportedOperationException(method.getName());
                                                    });
    }

    /**
     * A pump that is never run, so its storage is never asked for a connection.
     */
    private static ShardPump pump() {
        var dataSource = (DataSource) Proxy.newProxyInstance(ShardPumpWakeupMapTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        return new ShardPump(new ShardOwnedStorage(dataSource, (short) 0), ShardOwnerSettings.defaults().runtimeScoped(), new ShardOwnerMetrics(),
                             new AtomicBoolean(true), new AtomicBoolean(false), "pump-0", Optional::empty);
    }
}
