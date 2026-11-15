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

/**
 * Callbacks for metrics and diagnostics of the tail reader and the phase-2 subscription (design §1.2). Every method has
 * a no-op default; implementations must be fast and must not throw - they run on the reader's thread. A Micrometer
 * binding is a later step.
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
}
