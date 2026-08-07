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

import dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateArchiveRegistry;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.ESSENTIALS_ADMIN;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.SUBSCRIPTION_READER;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultAggregateArchiveApi implements AggregateArchiveApi {
    private final EssentialsSecurityProvider securityProvider;
    private final AggregateArchiveRegistry archiveRegistry;

    public DefaultAggregateArchiveApi(EssentialsSecurityProvider securityProvider,
                                      AggregateArchiveRegistry archiveRegistry) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.archiveRegistry = requireNonNull(archiveRegistry, "archiveRegistry must not be null");
    }

    @Override
    public Optional<ApiArchivedGeneration> findArchivedGeneration(Object principal,
                                                                  AggregateType aggregateType,
                                                                  String logicalAggregateId,
                                                                  long generation) {
        validateReadAccess(principal);
        requireNonNull(aggregateType, "aggregateType must not be null");
        requireNonNull(logicalAggregateId, "logicalAggregateId must not be null");
        return archiveRegistry.findArchivedGeneration(aggregateType, logicalAggregateId, generation)
                              .map(ApiArchivedGeneration::from);
    }

    @Override
    public List<ApiArchivedGeneration> findArchivedGenerations(Object principal,
                                                               AggregateType aggregateType,
                                                               String logicalAggregateId) {
        validateReadAccess(principal);
        requireNonNull(aggregateType, "aggregateType must not be null");
        requireNonNull(logicalAggregateId, "logicalAggregateId must not be null");
        return archiveRegistry.findArchivedGenerations(aggregateType, logicalAggregateId)
                              .stream()
                              .map(ApiArchivedGeneration::from)
                              .toList();
    }

    private void validateReadAccess(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);
    }
}
