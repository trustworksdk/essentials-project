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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The collaborators a {@link CdcDispatcher} runs with, as opposed to the configuration it runs under
 * ({@link CdcDispatcherSettings}).
 * <p>
 * The two previously-{@code Optional} parameters are plain nullable fields here, resolved in {@link Builder#build()}
 * to a no-op notifier and "no metrics" — the same defaults the dispatcher applied inline.
 */
public final class CdcDispatcherDependencies {
    private final CdcInboxRepository                                            inbox;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final EventStreamGapHandler<?>                                      eventStreamGapHandler;
    private final LogicalDecodingPlugin                                         logicalDecodingPlugin;
    private final CdcPoisonNotifier                                             cdcPoisonNotifier;
    private final Consumer<List<PersistedEvent>>                                onEvents;
    private final CdcAvailability                                               availability;
    private final MeterRegistry                                                 meterRegistry;

    private CdcDispatcherDependencies(CdcInboxRepository inbox,
                                      HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                      EventStreamGapHandler<?> eventStreamGapHandler,
                                      LogicalDecodingPlugin logicalDecodingPlugin,
                                      CdcPoisonNotifier cdcPoisonNotifier,
                                      Consumer<List<PersistedEvent>> onEvents,
                                      CdcAvailability availability,
                                      MeterRegistry meterRegistry) {
        this.inbox = inbox;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.eventStreamGapHandler = eventStreamGapHandler;
        this.logicalDecodingPlugin = logicalDecodingPlugin;
        this.cdcPoisonNotifier = cdcPoisonNotifier;
        this.onEvents = onEvents;
        this.availability = availability;
        this.meterRegistry = meterRegistry;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the staging table the dispatcher drains */
    public CdcInboxRepository inbox() {
        return inbox;
    }

    /** @return the unit-of-work factory used per dispatch batch */
    public HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory() {
        return unitOfWorkFactory;
    }

    /** @return the gap handler notified of permanent {@code GlobalEventOrder} gaps */
    public EventStreamGapHandler<?> eventStreamGapHandler() {
        return eventStreamGapHandler;
    }

    /** @return the plugin that decodes inbox payloads */
    public LogicalDecodingPlugin logicalDecodingPlugin() {
        return logicalDecodingPlugin;
    }

    /** @return the poison-row notifier. Never {@code null} — defaults to a no-op */
    public CdcPoisonNotifier cdcPoisonNotifier() {
        return cdcPoisonNotifier;
    }

    /** @return where decoded event batches are published */
    public Consumer<List<PersistedEvent>> onEvents() {
        return onEvents;
    }

    /** @return the shared CDC availability tracker */
    public CdcAvailability availability() {
        return availability;
    }

    /** @return the Micrometer registry, or {@code null} for no dispatcher metrics */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Builder for {@link CdcDispatcherDependencies}.
     */
    public static final class Builder {
        private CdcInboxRepository                                            inbox;
        private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private EventStreamGapHandler<?>                                      eventStreamGapHandler;
        private LogicalDecodingPlugin                                         logicalDecodingPlugin;
        private CdcPoisonNotifier                                             cdcPoisonNotifier;
        private Consumer<List<PersistedEvent>>                                onEvents;
        private CdcAvailability                                               availability;
        private MeterRegistry                                                 meterRegistry;

        /** @param inbox the staging table to drain. Required @return this builder */
        public Builder setInbox(CdcInboxRepository inbox) {
            this.inbox = inbox;
            return this;
        }

        /** @param unitOfWorkFactory the unit-of-work factory. Required @return this builder */
        public Builder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /** @param eventStreamGapHandler the gap handler. Required @return this builder */
        public Builder setEventStreamGapHandler(EventStreamGapHandler<?> eventStreamGapHandler) {
            this.eventStreamGapHandler = eventStreamGapHandler;
            return this;
        }

        /** @param logicalDecodingPlugin the decoding plugin. Required @return this builder */
        public Builder setLogicalDecodingPlugin(LogicalDecodingPlugin logicalDecodingPlugin) {
            this.logicalDecodingPlugin = logicalDecodingPlugin;
            return this;
        }

        /** @param cdcPoisonNotifier the poison-row notifier, or {@code null} for a no-op @return this builder */
        public Builder setCdcPoisonNotifier(CdcPoisonNotifier cdcPoisonNotifier) {
            this.cdcPoisonNotifier = cdcPoisonNotifier;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setCdcPoisonNotifier(CdcPoisonNotifier)}.
         *
         * @param cdcPoisonNotifier the notifier, or empty for a no-op
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setCdcPoisonNotifier(Optional<CdcPoisonNotifier> cdcPoisonNotifier) {
            requireNonNull(cdcPoisonNotifier, "cdcPoisonNotifier cannot be null");
            return setCdcPoisonNotifier(cdcPoisonNotifier.orElse(null));
        }

        /** @param onEvents where decoded batches are published. Required @return this builder */
        public Builder setOnEvents(Consumer<List<PersistedEvent>> onEvents) {
            this.onEvents = onEvents;
            return this;
        }

        /** @param availability the CDC availability tracker. Required @return this builder */
        public Builder setAvailability(CdcAvailability availability) {
            this.availability = availability;
            return this;
        }

        /** @param meterRegistry the Micrometer registry, or {@code null} for no metrics @return this builder */
        public Builder setMeterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry(MeterRegistry)}.
         *
         * @param meterRegistry the registry, or empty for no metrics
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
            requireNonNull(meterRegistry, "meterRegistry cannot be null");
            return setMeterRegistry(meterRegistry.orElse(null));
        }

        /** @return the new dependencies, with the neutral defaults applied */
        public CdcDispatcherDependencies build() {
            return new CdcDispatcherDependencies(requireNonNull(inbox, "inbox cannot be null"),
                                                 requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null"),
                                                 requireNonNull(eventStreamGapHandler, "eventStreamGapHandler cannot be null"),
                                                 requireNonNull(logicalDecodingPlugin, "logicalDecodingPlugin cannot be null"),
                                                 cdcPoisonNotifier != null ? cdcPoisonNotifier : new CdcPoisonNotifier.NoOpCdcPoisonNotifier(),
                                                 requireNonNull(onEvents, "onEvents cannot be null"),
                                                 requireNonNull(availability, "availability cannot be null"),
                                                 meterRegistry);
        }
    }
}
