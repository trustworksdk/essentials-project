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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueConsumers;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The reset rule of the processors whose handlers run on every instance ({@link PartitionedEventProcessor},
 * {@link PartitionedViewEventProcessor}): a reset runs only while the processor is stopped on <b>every</b> instance.
 * <p>
 * Why: such a processor's handlers run on several instances. A live reset on the instance that reads - what the other
 * processors do - purges the queue and truncates the view while a handler on another instance may still be running; it
 * then commits a row from before the reset into the reset view.
 * <p>
 * How it is checked, not assumed:
 * <ol>
 *     <li>The reset takes the processor's <b>reset lock</b> ({@code <processorName>:reset}). A starting instance holds the
 *     same lock while it registers its queue consumer ({@link #startConsumingExclusiveOfAReset}), so a start and a reset
 *     exclude each other: either the start registered its consumer before the reset counted, and the reset refuses, or
 *     the start waits until the reset has finished. A fenced lock is owned per lock manager, not per caller, so within one
 *     JVM a monitor per lock name does the same.</li>
 *     <li>It takes the <b>reader lock(s)</b>, so no instance reads and forwards events while it resets.</li>
 *     <li>It asks the queue implementation who may be handling the processor's queue, cluster-wide
 *     ({@code DurableQueues#getQueueConsumers}): no live consumer, and no departed instance still holding part of the
 *     queue. The second is an instance that crashed, or one whose stop kept part of the queue for a handler that is still
 *     running - the queue implementation cannot tell which, so both refuse. After a crash that no instance has recovered
 *     from, the operator either starts and cleanly stops an instance (it takes the crashed one's part over and releases
 *     it), or - having made sure the old process is gone - resets with {@code ignoreDepartedInstances}. A queue
 *     implementation that cannot tell is refused.</li>
 *     <li>Only then the reset itself runs: resume points, queue purge, {@code onSubscriptionsReset}.</li>
 * </ol>
 * Without {@code ignoreDepartedInstances} the check has no time bound: a departed instance counts for as long as it holds
 * part of the queue.
 */
final class StoppedEverywhereReset {
    private static final Logger log = LoggerFactory.getLogger(StoppedEverywhereReset.class);

    /**
     * How long a start waits for a reset in progress to finish before it gives up.
     */
    static final Duration START_WAITS_FOR_RESET = Duration.ofSeconds(60);

    private static final ConcurrentHashMap<LockName, ReentrantLock> LOCAL_MONITORS = new ConcurrentHashMap<>();

    private StoppedEverywhereReset() {
    }

    /**
     * @param processorName the processor's name
     * @return the lock a reset and a starting instance's consumer registration both hold
     */
    static LockName resetLockName(String processorName) {
        requireNonNull(processorName, "processorName is null");
        return LockName.of(processorName + ":reset");
    }

    /**
     * Run a start's consumer registration while holding the reset lock, so no reset counts consumers in the middle of it.
     *
     * @param processorName  the processor's name
     * @param lockManager    the lock manager the reset uses too
     * @param waitForReset   how long to wait for a reset in progress
     * @param startConsuming registers the queue consumer
     * @throws IllegalStateException if a reset is still in progress after {@code waitForReset}
     */
    static void startConsumingExclusiveOfAReset(String processorName, FencedLockManager lockManager, Duration waitForReset, Runnable startConsuming) {
        requireNonNull(lockManager, "lockManager is null");
        requireNonNull(waitForReset, "waitForReset is null");
        requireNonNull(startConsuming, "startConsuming is null");
        var resetLockName = resetLockName(processorName);
        var monitor       = LOCAL_MONITORS.computeIfAbsent(resetLockName, name -> new ReentrantLock());
        try {
            if (!monitor.tryLock(waitForReset.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw resetInProgress(processorName, resetLockName, waitForReset);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(msg("[{}] Interrupted while waiting for a reset to finish", processorName), e);
        }
        try {
            var resetLock = lockManager.tryAcquireLock(resetLockName, waitForReset)
                                       .orElseThrow(() -> resetInProgress(processorName, resetLockName, waitForReset));
            try {
                startConsuming.run();
            } finally {
                releaseLogging(processorName, resetLock);
            }
        } finally {
            monitor.unlock();
        }
    }

    private static IllegalStateException resetInProgress(String processorName, LockName resetLockName, Duration waited) {
        return new IllegalStateException(msg("[{}] Not started: a reset of the processor is in progress (lock '{}' held for more than {}). Start it again once the reset has finished",
                                             processorName, resetLockName, waited));
    }

    /**
     * Run a reset, refusing unless the processor is stopped on every instance - see the class javadoc.
     *
     * @param processorName             the processor's name
     * @param startedHere               the processor is started on this instance
     * @param lockManager               the lock manager the processor's start and readers use
     * @param readerLocks               the locks the processor's reader holds while it reads
     * @param queueDescription          names the processor's queue in a refusal
     * @param queueConsumers            asks the queue implementation who may be handling the queue
     * @param ignoreDepartedInstances   reset although departed instances still hold part of the queue - only once the
     *                                  caller has made sure their processes are gone
     * @param reset                     the reset itself
     * @throws IllegalStateException if the processor runs on this or another instance, a start or another reset holds the
     *                               reset lock, or the queue implementation cannot tell who may be handling the queue
     */
    static void reset(String processorName,
                      boolean startedHere,
                      FencedLockManager lockManager,
                      List<LockName> readerLocks,
                      String queueDescription,
                      Supplier<Optional<QueueConsumers>> queueConsumers,
                      boolean ignoreDepartedInstances,
                      Runnable reset) {
        requireNonNull(lockManager, "lockManager is null");
        requireNonNull(readerLocks, "readerLocks is null");
        requireNonNull(queueConsumers, "queueConsumers is null");
        requireNonNull(reset, "reset is null");
        if (startedHere) {
            throw new IllegalStateException(msg("[{}] Reset refused: the processor is started on this instance. Its handlers run on every instance, so it is reset only while " +
                                                "it is stopped on every instance - stop() it here and on every other instance first", processorName));
        }
        var resetLockName = resetLockName(processorName);
        var monitor       = LOCAL_MONITORS.computeIfAbsent(resetLockName, name -> new ReentrantLock());
        if (!monitor.tryLock()) {
            throw new IllegalStateException(msg("[{}] Reset refused: a start or another reset of the processor is in progress in this JVM", processorName));
        }
        var held = new ArrayList<FencedLock>();
        try {
            held.add(lockManager.tryAcquireLock(resetLockName)
                                .orElseThrow(() -> new IllegalStateException(msg("[{}] Reset refused: lock '{}' is held - an instance is starting the processor, or another reset is in progress",
                                                                                  processorName, resetLockName))));
            for (var readerLock : readerLocks) {
                held.add(lockManager.tryAcquireLock(readerLock)
                                    .orElseThrow(() -> new IllegalStateException(msg("[{}] Reset refused: lock '{}' is held, so an instance still reads the processor's events - " +
                                                                                     "stop the processor on every instance first", processorName, readerLock))));
            }
            var consumers = queueConsumers.get();
            if (consumers.isEmpty()) {
                throw new IllegalStateException(msg("[{}] Reset refused: the queue implementation cannot tell who consumes {}, so it cannot be checked that the " +
                                                    "processor is stopped on every instance", processorName, queueDescription));
            }
            if (consumers.get().liveInstances() > 0) {
                throw new IllegalStateException(msg("[{}] Reset refused: {} instance(s) still consume {}. Stop the processor on every instance first",
                                                    processorName, consumers.get().liveInstances(), queueDescription));
            }
            if (consumers.get().departedInstancesHoldingUnits() > 0) {
                if (!ignoreDepartedInstances) {
                    throw new IllegalStateException(msg("[{}] Reset refused: {} departed instance(s) still hold part of {} - an instance whose stop is waiting for a " +
                                                        "handler that is still running, or one that crashed. Wait for the handler to return; after a crash, start and " +
                                                        "cleanly stop an instance, or - once you have made sure the old process is gone - use the reset that ignores " +
                                                        "departed instances", processorName, consumers.get().departedInstancesHoldingUnits(), queueDescription));
                }
                log.warn("[{}] Resetting although {} departed instance(s) still hold part of {} - asked to ignore them: a handler still running there " +
                         "would write into the reset view", processorName, consumers.get().departedInstancesHoldingUnits(), queueDescription);
            }
            log.info("[{}] Stopped on every instance (no reader, no live consumer of {}) - resetting", processorName, queueDescription);
            reset.run();
        } finally {
            for (var i = held.size() - 1; i >= 0; i--) {
                releaseLogging(processorName, held.get(i));
            }
            monitor.unlock();
        }
    }

    private static void releaseLogging(String processorName, FencedLock lock) {
        try {
            lock.release();
        } catch (RuntimeException e) {
            log.warn("[{}] Failed to release lock '{}' - it is freed once it times out", processorName, lock.getName(), e);
        }
    }
}
