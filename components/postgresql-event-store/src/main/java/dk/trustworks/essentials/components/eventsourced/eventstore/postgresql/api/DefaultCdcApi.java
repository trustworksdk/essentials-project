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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.ESSENTIALS_ADMIN;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.SUBSCRIPTION_READER;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultCdcApi implements CdcApi {

    private final EssentialsSecurityProvider securityProvider;
    private final CdcAvailability            availability;
    private final CdcProperties              properties;
    private final String                     configuredSlotName;
    private final Optional<Wal2JsonTailer>   tailer;
    private final Optional<CdcDispatcher>    dispatcher;

    public DefaultCdcApi(EssentialsSecurityProvider securityProvider,
                         CdcAvailability availability,
                         CdcProperties properties,
                         String configuredSlotName,
                         Optional<Wal2JsonTailer> tailer,
                         Optional<CdcDispatcher> dispatcher) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.availability = requireNonNull(availability, "availability must not be null");
        this.properties = requireNonNull(properties, "properties must not be null");
        this.configuredSlotName = requireNonNull(configuredSlotName, "configuredSlotName must not be null");
        this.tailer = requireNonNull(tailer, "tailer must not be null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null");
    }

    @Override
    public ApiCdcStatus getStatus(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);

        var availabilitySnapshot = availability.snapshot();
        var effectiveSlotName = availabilitySnapshot.slotName() != null ? availabilitySnapshot.slotName() : configuredSlotName;

        return new ApiCdcStatus(
                ApiCdcAvailability.from(availabilitySnapshot),
                ApiCdcConfiguration.from(properties.isEnabled(), effectiveSlotName, properties),
                tailer.map(Wal2JsonTailer::getStatus).map(ApiCdcTailerStatus::from).orElse(null),
                dispatcher.map(CdcDispatcher::getStatus).map(ApiCdcDispatcherStatus::from).orElse(null)
        );
    }
}
