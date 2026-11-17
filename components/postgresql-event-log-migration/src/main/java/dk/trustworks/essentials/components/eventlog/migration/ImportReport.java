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
     * @param oldSubscriberId                the old subscriber
     * @param aggregateType                  the aggregate type
     * @param subscriber                     the new subscription's subscriber name
     * @param instanceNames                  the progress rows written ({@code ""} for an exclusive subscription)
     * @param oldResumeFrom                  {@code R}: the old row's {@code resume_from_and_including_global_eventorder}
     * @param resumeFrom                     {@code I = least(R, lowest open transient gap that has a row)}: the progress
     *                                       rows' {@code resume_from}
     * @param replayValues                   {@code R - I}: global order values below {@code R} delivered again
     * @param replayRows                     the events in {@code [I, R)}, delivered again (at least once)
     * @param openTransientGaps              the subscriber's open transient gaps of the type
     * @param filledOpenTransientGaps        those of them that have a row: committed late, maybe never delivered
     * @param lateFilledPermanentGapsBelowResume the permanent gaps of the type below {@code R} that have a row: late
     *                                       commits the old engine gave up on and never delivered; copied, not delivered
     *                                       (decision 7, report only)
     */
    public record SubscriptionImport(String oldSubscriberId, String aggregateType, String subscriber, List<String> instanceNames, long oldResumeFrom,
                                     long resumeFrom, long replayValues, long replayRows, long openTransientGaps, long filledOpenTransientGaps,
                                     long lateFilledPermanentGapsBelowResume) {
        public SubscriptionImport {
            instanceNames = List.copyOf(instanceNames);
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
