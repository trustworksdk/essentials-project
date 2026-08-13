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

package dk.trustworks.essentials.components.eventsourced.aggregates;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The pairing of an {@link AggregateType} with the aggregate implementation class that serves it.
 * <p>
 * This is the pair the aggregate-lifecycle subsystems are already keyed on - snapshot policies, closing-books
 * policies and generation access all need to know both halves - but until an application declares it, the framework
 * only ever sees one half at a time. Declaring it is what lets the framework read the policy annotations off an
 * aggregate root, which is not a Spring bean and therefore invisible to a {@code BeanPostProcessor}.
 *
 * @param aggregateType               the aggregate type whose event streams the aggregate is persisted to;
 *                                    must not be null
 * @param aggregateImplementationType the aggregate implementation class, i.e. the class carrying any
 *                                    {@code @AggregateSnapshotPolicy} / {@code @AggregateClosingBooksPolicy}
 *                                    annotation; must not be null
 * @see EssentialsAggregateDeclarations
 */
public record AggregateDeclaration(AggregateType aggregateType,
                                   Class<?> aggregateImplementationType) {
    public AggregateDeclaration {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
    }
}
