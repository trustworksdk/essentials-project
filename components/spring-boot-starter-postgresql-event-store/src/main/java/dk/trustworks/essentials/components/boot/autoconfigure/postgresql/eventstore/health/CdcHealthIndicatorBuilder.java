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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.health;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EssentialsEventStoreProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.*;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link CdcHealthIndicator}, obtained from {@link CdcHealthIndicator#builder()}.
 * <p>
 * The tailer and dispatcher are held as plain nullable fields — absent means the corresponding health details are
 * omitted rather than that anything is wrong, since a DIRECT-delivery deployment legitimately has no dispatcher — and
 * each also has an {@code Optional} overload, which is what the {@code @Bean} method that assembles this indicator
 * naturally holds.
 */
public final class CdcHealthIndicatorBuilder {
    private CdcAvailability                availability;
    private WalReplicationTailer           tailer;
    private CdcDispatcher                  dispatcher;
    private EssentialsEventStoreProperties properties;

    /**
     * @param availability the shared CDC availability tracker. Required
     * @return this builder instance for fluent chaining
     */
    public CdcHealthIndicatorBuilder setAvailability(CdcAvailability availability) {
        this.availability = availability;
        return this;
    }

    /**
     * @param tailer the WAL replication tailer, or {@code null} when none is configured
     * @return this builder instance for fluent chaining
     */
    public CdcHealthIndicatorBuilder setTailer(WalReplicationTailer tailer) {
        this.tailer = tailer;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setTailer(WalReplicationTailer)}.
     *
     * @param tailer the tailer, or empty when none is configured
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcHealthIndicatorBuilder setTailer(Optional<WalReplicationTailer> tailer) {
        requireNonNull(tailer, "tailer cannot be null");
        return setTailer(tailer.orElse(null));
    }

    /**
     * @param dispatcher the CDC dispatcher, or {@code null} — DIRECT delivery has none
     * @return this builder instance for fluent chaining
     */
    public CdcHealthIndicatorBuilder setDispatcher(CdcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setDispatcher(CdcDispatcher)}.
     *
     * @param dispatcher the dispatcher, or empty
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcHealthIndicatorBuilder setDispatcher(Optional<CdcDispatcher> dispatcher) {
        requireNonNull(dispatcher, "dispatcher cannot be null");
        return setDispatcher(dispatcher.orElse(null));
    }

    /**
     * @param properties the event-store properties, read for the configured {@link CdcMode}. Required
     * @return this builder instance for fluent chaining
     */
    public CdcHealthIndicatorBuilder setProperties(EssentialsEventStoreProperties properties) {
        this.properties = properties;
        return this;
    }

    /**
     * Builds the health indicator.
     *
     * @return the indicator
     */
    @SuppressWarnings("removal")
    public CdcHealthIndicator build() {
        return new CdcHealthIndicator(requireNonNull(availability, "availability cannot be null"),
                                      Optional.ofNullable(tailer),
                                      Optional.ofNullable(dispatcher),
                                      requireNonNull(properties, "properties cannot be null"));
    }
}
