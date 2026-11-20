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
package dk.trustworks.essentials.components.eventlog;

import java.util.*;

/**
 * <b>Test-only</b> seams of the tail reader and the subscription: the mutants the invariant ITs must be seen to catch,
 * hooks into the reader loop's thread and the delivery thread at the points the invariants care about, and a read
 * source in place of the event table (a view that holds a scan open, for the hold-inside-scan proofs). Package-private;
 * {@link #NONE} in normal use.
 * <p>
 * The tail reader of an aggregate type is shared: its mutants, reader hooks and read source are those of
 * the subscription that made the type active (the first one to start); the subscription mutants and delivery-thread
 * hooks are each subscription's own.
 *
 * @param mutants        broken variants to switch on
 * @param hooks          callbacks on the reader loop's and the delivery thread
 * @param readTable      a relation to read instead of the aggregate type's event table, or {@code null}
 */
record TestSeams(Set<WatermarkMutant> mutants, Hooks hooks, String readTable) {
    static final TestSeams NONE = new TestSeams(Set.of(), Hooks.NONE, null);

    TestSeams {
        mutants = mutants.isEmpty() ? Set.of() : EnumSet.copyOf(mutants);
    }

    boolean has(WatermarkMutant mutant) {
        return mutants.contains(mutant);
    }

    /**
     * Thrown by a hook to end the delivery thread as a lost JVM would: no further statement, no rollback, no close, no
     * final save. The IT then terminates the open page connection's backend ({@link Subscription#openPageConnection()}),
     * so the transaction dies with it.
     */
    static final class Crash extends RuntimeException {
        Crash(String where) {
            super("Test crash at " + where, null, false, false);
        }
    }

    interface Hooks {
        Hooks NONE = new Hooks() {
        };

        /** Before the loop starts the type's reader (at activation and after every reconnect); loop thread */
        default void beforeReaderStart(String aggregateType) throws Exception {
        }

        /** Before every read, plain or combined */
        default void beforeRead(boolean combined) throws Exception {
        }

        /** {@link WatermarkMutant#RUNNING_SET_BEFORE_TAIL_READ} only: between the two statements */
        default void afterRunningSetRead() throws Exception {
        }

        /** After a candidate retired and before its hole read is sent; {@code holes} are the values to re-read */
        default void beforeHoleRead(Collection<Long> holes) throws Exception {
        }

        /** Reader loop thread: a read finished and its rows are not yet offered to any inbox */
        default void afterReadBeforePublish(String aggregateType, List<StoredEvent> rows) throws Exception {
        }

        /**
         * Reader loop thread, at the quiescent point: the subscription's inbox was just attached with cursor {@code C}
         * and watermark {@code W}. The attaching subscription's hook
         */
        default void onAttach(String subscriber, long cursor, long watermark) throws Exception {
        }

        /** Delivery thread: the catch-up reached the watermark and the attach request is about to be sent */
        default void beforeAttachRequest(String subscriber) throws Exception {
        }

        /** Delivery thread: between two bridge pages; {@code lowerBound} is the next page's (exclusive) lower bound */
        default void betweenBridgePages(String subscriber, long lowerBound) throws Exception {
        }

        /** Delivery thread: the subscription detached and catches up from {@code resumeFrom} */
        default void onDetach(String subscriber, String reason, long resumeFrom) throws Exception {
        }

        // ---- catch-up and bridge reads: delivery thread ----

        /** Before a catch-up read ({@code deferred = false}) or a {@code CONTIGUOUS} deferred-bridge read ({@code true}) */
        default void beforeDeliveryRead(String subscriber, boolean deferred) throws Exception {
        }

        /** The page transaction is open; the handler is about to run on {@code page} */
        default void beforePageHandler(String subscriber, List<StoredEvent> page) throws Exception {
        }

        /** The handler returned; the progress update is next */
        default void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) throws Exception {
        }

        /** The progress update ran (in the page transaction); the commit is next */
        default void afterProgressBeforeCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
        }

        /** The page transaction committed; the applied prefix is not yet moved in memory */
        default void afterCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
        }

        /** {@code CONTIGUOUS}: {@code rows} reach pending, justified by the received watermark {@code receivedWatermark} */
        default void onRelease(String subscriber, List<StoredEvent> rows, long receivedWatermark) throws Exception {
        }

        /** An event is parked ({@code blockedBy} null) or held behind a parked head */
        default void onPark(String subscriber, long globalOrder, Long blockedBy) throws Exception {
        }

        // ---- filtered reads: delivery thread ----

        /** A filtered catch-up read its ceiling {@code ceiling} and is about to resolve its type ids */
        default void afterCeilingBeforeTypeIds(String subscriber, long ceiling) throws Exception {
        }

        /** A filtered read of {@code (lower, ceiling]} is about to run with type ids {@code typeIds} */
        default void beforeFilteredRead(String subscriber, long lower, long ceiling, Set<Short> typeIds) throws Exception {
        }

        /** A catch-up permit was acquired ({@code true}) or released ({@code false}) */
        default void onPermit(String subscriber, boolean acquired) throws Exception {
        }

        // ---- attach under an unknown watermark ----

        /** Delivery thread: the bridge under an unknown watermark reached {@code C}; {@code gaps} are about to be handed off */
        default void beforeHandOff(String subscriber, List<HandledSet.Gap> gaps) throws Exception {
        }

        /**
         * Reader loop thread, at the quiescent point: {@code gaps} were handed off and are about to be added to the reader's
         * holes. The handing subscription's hook
         */
        default void onHandOff(String aggregateType, List<HandledSet.Gap> gaps) throws Exception {
        }

        // ---- reader loop thread ----

        /**
         * The due types' statements are about to be sent: one combined or tail statement for a single type, one flush for
         * several. {@code wantsRunningSet}: a running set is read with them. The hook of every due type's first
         * subscription, once per distinct hook
         */
        default void beforeFlush(List<String> aggregateTypes, boolean wantsRunningSet) throws Exception {
        }

        /** The due types' statements returned; their results are not yet applied to any reader */
        default void afterFlushBeforeApply(List<String> aggregateTypes) throws Exception {
        }

        // ---- wake-ups: the event log's wake seams ----

        /** Notifier thread: {@code payloads} are about to be sent as one {@code NOTIFY} statement */
        default void beforeNotifySend(List<String> payloads) throws Exception {
        }

        /** Listener thread: a notification arrived on the wake channel, before it is parsed or filtered */
        default void onNotification(String payload) throws Exception {
        }

        /** Listener thread: a restarted listener is about to take a new connection and re-run {@code LISTEN} */
        default void beforeListenerReconnect() throws Exception {
        }

        // ---- leases: the lease keeper's thread, on EventLog.leaseSeams ----

        /** Keeper: the claim statement for {@code keys} (standby subscriptions) is about to be sent */
        default void beforeClaim(List<ProgressStore.Key> keys) throws Exception {
        }

        /** Keeper: the renewal statement for {@code keys} (owned leases) is about to be sent; its confirmation time is taken */
        default void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
        }

        /** Keeper: the renewal returned; the leases it renewed are not yet confirmed */
        default void afterRenewalBeforeConfirm(List<ProgressStore.Key> keys) throws Exception {
        }

        // ---- leases: delivery thread ----

        /** A periodic save of {@code resumeFrom} passed its deadline check and is about to be sent */
        default void beforePeriodicSave(String subscriber, long resumeFrom) throws Exception {
        }

        /** A periodic park insert of {@code globalOrder} passed its deadline check and is about to be sent */
        default void beforePeriodicPark(String subscriber, long globalOrder) throws Exception {
        }

        /** A periodic handler call on {@code event} passed its deadline check and is about to be made */
        default void beforeHandler(String subscriber, StoredEvent event) throws Exception {
        }
    }
}
