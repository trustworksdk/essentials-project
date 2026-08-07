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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

/**
 * Shared field exclusions for tests that compare an aggregate restored from a snapshot against the live aggregate the
 * snapshot was taken from.
 */
final class AggregateSnapshotComparison {

    private AggregateSnapshotComparison() {
    }

    /**
     * A snapshot only carries domain state; the framework runtime state of a restored aggregate comes from the snapshot
     * metadata instead, which makes it legitimately different from that of the live instance — a restored aggregate
     * counts as rehydrated, is positioned at the snapshot's last-included event order, and has no uncommitted events.
     * {@code invoker} is excluded as well, being a per-instance reflection cache.
     */
    static final String[] FRAMEWORK_RUNTIME_FIELDS = {
            "invoker",
            "hasBeenRehydrated",
            "isRehydrating",
            "uncommittedEvents",
            "eventOrderOfLastAppliedEvent",
            "eventOrderOfLastRehydratedEvent"
    };
}
