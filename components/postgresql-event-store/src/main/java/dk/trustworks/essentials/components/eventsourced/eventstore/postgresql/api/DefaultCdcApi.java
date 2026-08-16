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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.ESSENTIALS_ADMIN;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.SUBSCRIPTION_READER;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultCdcApi implements CdcApi {

    private final EssentialsSecurityProvider securityProvider;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final CdcAvailability            availability;
    private final CdcProperties              properties;
    private final String                     configuredSlotName;
    private final Optional<WalReplicationTailer> tailer;
    private final Optional<CdcDispatcher>    dispatcher;

    /**
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public DefaultCdcApi(EssentialsSecurityProvider securityProvider,
                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                         CdcAvailability availability,
                         CdcProperties properties,
                         String configuredSlotName,
                         Optional<WalReplicationTailer> tailer,
                         Optional<CdcDispatcher> dispatcher) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
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
        var slotStatus = unitOfWorkFactory.withUnitOfWork(uow -> {
            var slot = PgReplicationSlots.findSlot(uow.handle().getConnection(), effectiveSlotName);
            return slot != null
                   ? ApiCdcSlotStatus.from(slot, properties.getSlot().getMode(), properties.getPlugin())
                   : ApiCdcSlotStatus.missing(effectiveSlotName, properties.getSlot().getMode(), properties.getPlugin());
        });

        return new ApiCdcStatus(
                ApiCdcAvailability.from(availabilitySnapshot),
                ApiCdcConfiguration.from(properties.isEnabled(), effectiveSlotName, properties),
                slotStatus,
                tailer.map(WalReplicationTailer::getStatus).map(ApiCdcTailerStatus::from).orElse(null),
                dispatcher.map(CdcDispatcher::getStatus).map(ApiCdcDispatcherStatus::from).orElse(null)
        );
    }

    /**
     * Creates a builder for a {@link DefaultCdcApi}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DefaultCdcApi}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder {
        private EssentialsSecurityProvider securityProvider;
        private EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
        private CdcAvailability availability;
        private CdcProperties properties;
        private String configuredSlotName;
        private WalReplicationTailer tailer;
        private CdcDispatcher dispatcher;

        /**
         * @param securityProvider required
         * @return this builder
         */
        public Builder setSecurityProvider(EssentialsSecurityProvider securityProvider) {
            this.securityProvider = securityProvider;
            return this;
        }

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder setUnitOfWorkFactory(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param availability required
         * @return this builder
         */
        public Builder setAvailability(CdcAvailability availability) {
            this.availability = availability;
            return this;
        }

        /**
         * @param properties required
         * @return this builder
         */
        public Builder setProperties(CdcProperties properties) {
            this.properties = properties;
            return this;
        }

        /**
         * @param configuredSlotName required
         * @return this builder
         */
        public Builder setConfiguredSlotName(String configuredSlotName) {
            this.configuredSlotName = configuredSlotName;
            return this;
        }

        /**
         * @param tailer optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setTailer(WalReplicationTailer tailer) {
            this.tailer = tailer;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setTailer}.
         *
         * @param tailer the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setTailer(Optional<WalReplicationTailer> tailer) {
            requireNonNull(tailer, "No tailer provided");
            return setTailer(tailer.orElse(null));
        }

        /**
         * @param dispatcher optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setDispatcher(CdcDispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setDispatcher}.
         *
         * @param dispatcher the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setDispatcher(Optional<CdcDispatcher> dispatcher) {
            requireNonNull(dispatcher, "No dispatcher provided");
            return setDispatcher(dispatcher.orElse(null));
        }

        /**
         * @return the new {@link DefaultCdcApi}
         */
        @SuppressWarnings("removal")
        public DefaultCdcApi build() {
            return new DefaultCdcApi(securityProvider,
                                     unitOfWorkFactory,
                                     availability,
                                     properties,
                                     configuredSlotName,
                                     Optional.ofNullable(tailer),
                                     Optional.ofNullable(dispatcher));
        }
    }

}
