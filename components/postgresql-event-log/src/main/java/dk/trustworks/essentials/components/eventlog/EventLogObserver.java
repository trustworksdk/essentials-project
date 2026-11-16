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

import java.time.Duration;
import java.util.Set;

/**
 * Callbacks for metrics and diagnostics of the tail reader and the subscriptions (design §1.2, phase 3 §1.1). Every
 * method has a no-op default; implementations must be fast and must not throw - they run on the reader loop's thread
 * or a subscription's delivery thread. A Micrometer binding is a later step.
 */
public interface EventLogObserver {
    EventLogObserver NONE = new EventLogObserver() {
    };

    /**
     * One tail read or combined probe finished.
     *
     * @param aggregateType         the reader's aggregate type
     * @param combined              {@code true} for the combined probe (running set read), {@code false} for the plain tail
     * @param nanos                 the statement's wall-clock time
     * @param rows                  event rows returned (late commits included)
     * @param outstandingHoles      values or ranges not yet resolved after the read
     * @param outstandingCandidates candidates not yet retired after the read
     * @param oldestCandidateAge    age of the oldest outstanding candidate, {@link Duration#ZERO} if none (plan §4.6)
     */
    default void onRead(String aggregateType, boolean combined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, Duration oldestCandidateAge) {
    }

    /**
     * A candidate retired and its holes were re-read.
     *
     * @param resolvedHoles values or ranges resolved
     * @param lateRows      late commits the hole read returned
     */
    default void onHoleRead(String aggregateType, int resolvedHoles, int lateRows) {
    }

    /**
     * A subscription saved its resume point.
     */
    default void onProgressSaved(String subscriber, String aggregateType, long resumeFrom) {
    }

    /**
     * A reader or handler failed; the subscription retries.
     */
    default void onError(String subscriber, String aggregateType, Throwable error) {
    }

    /**
     * A subscription's inbox was attached to its aggregate type's reader (phase 3 §3.4). Reader loop thread.
     *
     * @param cursor    the reader's cursor {@code C}: the bridge reads up to it
     * @param watermark the reader's watermark {@code W}
     */
    default void onAttach(String subscriber, String aggregateType, long cursor, long watermark) {
    }

    /**
     * A subscription detached (phase 3 §3.6) and catches up from {@code resumeFrom}. Delivery thread.
     *
     * @param reason {@code OVERFLOW}, {@code READER_RESTART}, {@code RESET} or {@code STOPPED}
     */
    default void onDetach(String subscriber, String aggregateType, String reason, long resumeFrom) {
    }

    /**
     * One catch-up or bridge statement finished (phase 3 §3.3, §3.4). Delivery thread.
     *
     * @param bridge {@code true} for a bridge page
     * @param rows   rows returned
     */
    default void onCatchUpRead(String subscriber, String aggregateType, boolean bridge, long nanos, int rows) {
    }

    /**
     * An attached subscription took a batch from its inbox. Delivery thread.
     *
     * @param rows        rows in the batch
     * @param inboxEvents events still in the inbox after the take: the inbox depth
     */
    default void onBatchTaken(String subscriber, String aggregateType, int rows, int inboxEvents) {
    }

    /**
     * A subscription started or stopped waiting because its aggregate type's watermark is unknown (F4, phase 3
     * decision 2): after a reader (re)start while a writer holds a value below the head. Delivery thread.
     *
     * @param waiting {@code true} when the wait starts, {@code false} when it ends
     * @param heldBy  the {@code backend_xid}s of the reader's initial candidate - the writers the wait is for; empty
     *                when the reader has not started yet, or when the wait ends
     */
    default void onWaitingForWatermark(String subscriber, String aggregateType, boolean waiting, Set<String> heldBy) {
    }

    /**
     * An in-transaction page committed together with its resume point (phase 4 §3.3). Delivery thread.
     *
     * @param size events in the page
     */
    default void onPageCommitted(String subscriber, String aggregateType, int size) {
    }

    /**
     * A page transaction rolled back (phase 4 §4). Delivery thread.
     *
     * @param size  events in the page
     * @param cause what failed
     */
    default void onPageFailed(String subscriber, String aggregateType, int size, Throwable cause) {
    }

    /**
     * {@code CONTIGUOUS}: rows moved to pending under a received watermark (phase 4 §2.2). Delivery thread.
     *
     * @param rows              rows released
     * @param receivedWatermark the received watermark that covers them
     * @param heldBack          rows still held back above it
     */
    default void onReleased(String subscriber, String aggregateType, int rows, long receivedWatermark, int heldBack) {
    }

    /**
     * An event was parked (phase 4 §4.4), or held behind its aggregate's parked head (§4.5); the row is committed.
     * Delivery thread.
     *
     * @param blockedBy the parked head this event is held behind, or {@code null} if it is a head itself
     * @param attempts  how often the handler failed on it alone ({@code 0} for an event held behind a head)
     */
    default void onParked(String subscriber, String aggregateType, long globalOrder, Long blockedBy, int attempts) {
    }

    /**
     * The number of aggregates held behind a parked head changed, or was loaded at start (phase 4 §4.5). Delivery thread.
     */
    default void onHeldAggregates(String subscriber, String aggregateType, int count) {
    }
}
