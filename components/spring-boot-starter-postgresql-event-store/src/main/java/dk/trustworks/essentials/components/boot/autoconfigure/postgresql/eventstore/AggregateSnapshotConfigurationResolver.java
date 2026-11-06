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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

/**
 * Interface defining the contract for resolving the configuration required to manage
 * Aggregate snapshot operations in an event-sourcing setup.
 * <p>
 * An application-supplied implementation that reads the starter's default {@code AggregateSnapshotPolicyRegistry} sees
 * the policies of the {@code EssentialsAggregateDeclarations}-declared aggregates, also while the application's beans
 * are still being built; nothing has to wait for {@link AggregateDeclarationPolicyRegistrar}. The one exception is a
 * read made while an {@code EssentialsAggregateDeclarations} bean (or something it depends on) is itself being
 * created, which sees the registry without them. The starter does not do this for an application-supplied registry:
 * such a registry has the declared policies before the registrar runs only when it registers them itself (see
 * {@link AggregateDeclarationPolicyRegistrar#registerDeclaredSnapshotPolicies}).
 */
public interface AggregateSnapshotConfigurationResolver {

    /**
     * Resolves and retrieves the snapshot configuration for a specified Aggregate type and its implementation.
     *
     * @param aggregateType the type of the aggregate for which the snapshot configuration is being resolved
     * @param aggregateImplementationType the class type representing the specific implementation of the aggregate
     * @return the resolved aggregate snapshot configuration containing snapshot settings such as enablement,
     *         execution mode, and retention policies
     */
    ResolvedAggregateSnapshotConfiguration resolve(AggregateType aggregateType,
                                                   Class<?> aggregateImplementationType);
}
