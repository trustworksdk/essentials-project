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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

public record ApiAggregateSnapshotPolicy(
        AggregateType aggregateType,
        String aggregateImplementationType,
        boolean enabled,
        SnapshotExecutionMode mode,
        int everyNEvents,
        SnapshotDeletionMode deletionMode,
        int keepLastSnapshots
) {
    public static ApiAggregateSnapshotPolicy from(AggregateSnapshotPolicyDescriptor descriptor) {
        var policy = descriptor.policy();
        return new ApiAggregateSnapshotPolicy(descriptor.aggregateType().map(AggregateType::of).orElse(null),
                                              descriptor.aggregateImplementationType().getName(),
                                              policy.enabled(),
                                              policy.mode(),
                                              policy.everyNEvents(),
                                              policy.deletionMode(),
                                              policy.keepLastSnapshots());
    }
}
