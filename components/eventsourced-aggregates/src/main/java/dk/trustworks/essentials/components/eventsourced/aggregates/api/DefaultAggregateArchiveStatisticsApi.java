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
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.ESSENTIALS_ADMIN;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.SUBSCRIPTION_READER;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultAggregateArchiveStatisticsApi implements AggregateArchiveStatisticsApi {
    private final EssentialsSecurityProvider securityProvider;
    private final AggregateArchiveRegistry archiveRegistry;

    public DefaultAggregateArchiveStatisticsApi(EssentialsSecurityProvider securityProvider,
                                                AggregateArchiveRegistry archiveRegistry) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.archiveRegistry = requireNonNull(archiveRegistry, "archiveRegistry must not be null");
    }

    @Override
    public List<ApiAggregateArchiveStatistics> findAggregateArchiveStatistics(Object principal) {
        validateReadAccess(principal);
        return archiveRegistry.summarizeArchivedGenerations()
                              .stream()
                              .map(summary -> new ApiAggregateArchiveStatistics(summary.aggregateType(),
                                                                                summary.archivedGenerationCount(),
                                                                                summary.failedGenerationCount(),
                                                                                summary.totalArchivedEventCount(),
                                                                                summary.lastArchivedAt()))
                              .toList();
    }

    private void validateReadAccess(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);
    }
}
