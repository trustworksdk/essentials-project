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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Logs the {@link GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException}s of the
 * {@link DefaultEventStoreSubscriptionManager}'s periodic and threshold saves without flooding the log: a resume point whose
 * gaps cannot be recorded stays changed, so every following save - the threshold check runs as often as every 50 ms - fails
 * for it again.
 * <p>
 * Per subscriber and aggregate type: the first failure is a WARN with the stack trace, the failures after it are DEBUG, and
 * once {@link #SUMMARY_INTERVAL} has passed since its last WARN the next failure is one WARN line, without a stack trace,
 * counting them. A save that no longer fails for a subscriber and aggregate type forgets it, so its next failure is a WARN
 * with the stack trace again.
 * <p>
 * Not thread-safe: used only on the manager's single resume-point scheduler thread
 */
final class GapsNotRecordedWarnings {
    static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(1);

    private final Logger       log;
    private final LongSupplier nanoTime;
    /**
     * Each subscriber and aggregate type whose resume point the last save that included it could not save
     */
    private final Map<Pair<SubscriberId, AggregateType>, Failing> failing = new HashMap<>();

    GapsNotRecordedWarnings(Logger log, LongSupplier nanoTime) {
        this.log = requireNonNull(log, "No log provided");
        this.nanoTime = requireNonNull(nanoTime, "No nanoTime provided");
    }

    /**
     * A save of {@code resumePoints} returned without a {@link GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException}:
     * forgets their subscribers and aggregate types. Costs nothing while no save has failed
     */
    void saved(Collection<SubscriptionResumePoint> resumePoints) {
        if (failing.isEmpty()) {
            return;
        }
        resumePoints.forEach(resumePoint -> failing.remove(Pair.of(resumePoint.getSubscriberId(), resumePoint.getAggregateType())));
    }

    /**
     * A save of {@code resumePoints} threw {@code e}: logs it, and forgets the subscribers and aggregate types of the
     * {@code resumePoints} it does not name
     */
    void notSaved(Collection<SubscriptionResumePoint> resumePoints, GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException e) {
        var notRecorded = e.notRecorded();
        if (!failing.isEmpty()) {
            resumePoints.forEach(resumePoint -> {
                var key = Pair.of(resumePoint.getSubscriberId(), resumePoint.getAggregateType());
                if (!notRecorded.containsKey(key)) {
                    failing.remove(key);
                }
            });
        }
        var now = nanoTime.getAsLong();
        if (notRecorded.keySet().stream().anyMatch(key -> !failing.containsKey(key))) {
            log.warn("Not all ResumePoint's were stored - the next periodic save tries again. While the same subscriber's resume point keeps failing, " +
                             "its failures are logged at DEBUG, with one WARN a minute counting them", e);
            notRecorded.keySet().forEach(key -> failing.put(key, new Failing(now)));
            return;
        }
        var summarised = 0;
        for (var entry : notRecorded.entrySet()) {
            var key        = entry.getKey();
            var stillFails = failing.get(key);
            stillFails.failuresSinceWarn++;
            if (now - stillFails.lastWarnAt >= SUMMARY_INTERVAL.toNanos()) {
                log.warn("[{}-{}] Resume point still not stored - it failed {} time(s) since the last WARN, as the gaps kept in memory could not be recorded. Latest: {}",
                         key._1, key._2, stillFails.failuresSinceWarn, entry.getValue());
                stillFails.lastWarnAt = now;
                stillFails.failuresSinceWarn = 0;
                summarised++;
            }
        }
        if (summarised < notRecorded.size()) {
            log.debug("Not all ResumePoint's were stored - the next periodic save tries again: {}", e.toString());
        }
    }

    private static final class Failing {
        private long lastWarnAt;
        private long failuresSinceWarn;

        private Failing(long lastWarnAt) {
            this.lastWarnAt = lastWarnAt;
        }
    }
}
