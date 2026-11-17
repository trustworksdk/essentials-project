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

package dk.trustworks.essentials.components.eventlog.migration;

import java.util.List;

/**
 * What the import of resume points did, and what it could not recover (design phase 9 §4.2, §4.4, decisions 7 and 8).
 *
 * @param imported     one entry per imported subscription
 * @param unmapped     the old durable subscriptions of the plan's aggregate types that no {@link ImportedSubscription}
 *                     maps; not imported, so each new subscription of theirs starts where its {@code StartFrom} says.
 *                     A CDC consumer's position is such a row too, under its group-namespaced subscriber id
 * @param gapTracking  whether the old engine's {@code transient_subscriber_gaps} table exists. Without it (an application
 *                     on {@code NoEventStreamGapHandler}) every import is {@code I = R}: late commits the old engine
 *                     skipped cannot be identified
 */
public record ImportReport(List<SubscriptionImport> imported, List<UnmappedSubscriber> unmapped, boolean gapTracking) {
    public ImportReport {
        imported = List.copyOf(imported);
        unmapped = List.copyOf(unmapped);
    }

    /**
     * A safe replay longer than this many rows is flagged ({@link SubscriptionImport#largeReplay()}), with a hint about
     * {@link ImportedSubscription#trustOldResumePoint()}
     */
    public static final long LARGE_REPLAY_ROWS = 10_000;

    /** At most this many skipped filled gaps are listed per mapping ({@link SubscriptionImport#skippedFilledGaps()}) */
    public static final int MAX_LISTED_GAPS = 100;

    /**
     * @param oldSubscriberId                the old subscriber
     * @param aggregateType                  the aggregate type
     * @param subscriber                     the new subscription's subscriber name
     * @param instanceNames                  the progress rows written ({@code ""} for an exclusive subscription)
     * @param oldResumeFrom                  {@code R}: the old row's {@code resume_from_and_including_global_eventorder}
     * @param resumeFrom                     the progress rows' {@code resume_from}: {@code safeResumeFrom}, or {@code R} when
     *                                       {@code trustedOldResumePoint}
     * @param replayValues                   {@code R - resumeFrom}: global order values below {@code R} delivered again
     * @param replayRows                     the events in {@code [resumeFrom, R)}, delivered again (at least once)
     * @param openTransientGaps              the subscriber's open transient gaps of the type
     * @param filledOpenTransientGaps        those of them that have a row: committed late, maybe never delivered
     * @param lateFilledPermanentGapsBelowResume the permanent gaps of the type below {@code R} that have a row: late
     *                                       commits the old engine gave up on and never delivered; copied, not delivered
     *                                       (decision 7, report only)
     * @param safeResumeFrom                 the I8-safe {@code I = least(R, lowest open transient gap that has a row)},
     *                                       whether imported or not
     * @param safeReplayRows                 the events in {@code [safeResumeFrom, R)}: the replay the safe value costs
     * @param trustedOldResumePoint          whether the mapping opted in to importing {@code R}
     *                                       ({@link ImportedSubscription#trustOldResumePoint()})
     * @param skippedFilledGaps              when {@code trustedOldResumePoint}: the filled open transient gaps below
     *                                       {@code R}, lowest first and at most {@link #MAX_LISTED_GAPS}, whose events the
     *                                       subscription will not be delivered again; the operator stated they were handled.
     *                                       Empty otherwise
     * @param skippedFilledGapCount          when {@code trustedOldResumePoint}: how many filled open transient gaps below
     *                                       {@code R} there are in all; 0 otherwise
     */
    public record SubscriptionImport(String oldSubscriberId, String aggregateType, String subscriber, List<String> instanceNames, long oldResumeFrom,
                                     long resumeFrom, long replayValues, long replayRows, long openTransientGaps, long filledOpenTransientGaps,
                                     long lateFilledPermanentGapsBelowResume, long safeResumeFrom, long safeReplayRows, boolean trustedOldResumePoint,
                                     List<Long> skippedFilledGaps, long skippedFilledGapCount) {
        public SubscriptionImport {
            instanceNames = List.copyOf(instanceNames);
            skippedFilledGaps = List.copyOf(skippedFilledGaps);
        }

        /**
         * @return whether the safe value replays more than {@link #LARGE_REPLAY_ROWS} rows and the mapping has not opted in
         * to the old resume point: an operator who knows the old subscriber handled its gap fills may set
         * {@link ImportedSubscription#trustOldResumePoint()} to skip the replay
         */
        public boolean largeReplay() {
            return !trustedOldResumePoint && safeReplayRows > LARGE_REPLAY_ROWS;
        }
    }

    /**
     * @param subscriberId  the old subscriber
     * @param aggregateType the aggregate type
     * @param resumeFrom    its old resume point; null if the old row has none
     */
    public record UnmappedSubscriber(String subscriberId, String aggregateType, Long resumeFrom) {
    }
}
