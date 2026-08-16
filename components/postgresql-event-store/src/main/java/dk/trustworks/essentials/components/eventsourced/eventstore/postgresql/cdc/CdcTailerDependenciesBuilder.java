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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.WalMessageFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link CdcTailerDependencies}, obtained from {@link CdcTailerDependencies#builder()}.
 * <p>
 * The optional collaborators are held as plain nullable fields and resolved in {@link #build()} — the neutral defaults
 * being {@link DefaultWalReplicationTailerErrorHandler} and an empty table-name supplier. Each also has an
 * {@code Optional} overload, for Spring {@code @Bean} methods where an {@code Optional} injection point is idiomatic.
 */
public final class CdcTailerDependenciesBuilder {
    private DataSource                                                   replicationDataSource;
    private Jdbi                                                         jdbi;
    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private LogicalDecodingPlugin                                        logicalDecodingPlugin;
    private CdcAvailability                                              availability;
    private MeterRegistry                                                meterRegistry;
    private WalReplicationTailerErrorHandler                             errorHandler;
    private WalMessageFilter                                             walMessageFilter;
    private Supplier<Set<String>>                                        eventStreamTableNamesSupplier;

    /**
     * @param replicationDataSource the replication-enabled DataSource the tailer opens its slot on. Required
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setReplicationDataSource(DataSource replicationDataSource) {
        this.replicationDataSource = replicationDataSource;
        return this;
    }

    /**
     * @param jdbi the Jdbi instance. Required
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setJdbi(Jdbi jdbi) {
        this.jdbi = jdbi;
        return this;
    }

    /**
     * @param unitOfWorkFactory the unit-of-work factory used for inbox writes and slot bookkeeping. Required
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param logicalDecodingPlugin the plugin that decodes raw WAL payloads. Required
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setLogicalDecodingPlugin(LogicalDecodingPlugin logicalDecodingPlugin) {
        this.logicalDecodingPlugin = logicalDecodingPlugin;
        return this;
    }

    /**
     * @param availability the shared CDC availability tracker. Required
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setAvailability(CdcAvailability availability) {
        this.availability = availability;
        return this;
    }

    /**
     * @param meterRegistry the Micrometer registry, or {@code null} for no tailer metrics
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setMeterRegistry(MeterRegistry)}.
     *
     * @param meterRegistry the registry, or empty for no metrics
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcTailerDependenciesBuilder setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
        requireNonNull(meterRegistry, "meterRegistry cannot be null");
        return setMeterRegistry(meterRegistry.orElse(null));
    }

    /**
     * @param errorHandler the replication error handler, or {@code null} for {@link DefaultWalReplicationTailerErrorHandler}
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setErrorHandler(WalReplicationTailerErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setErrorHandler(WalReplicationTailerErrorHandler)}.
     *
     * @param errorHandler the handler, or empty for the default
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcTailerDependenciesBuilder setErrorHandler(Optional<WalReplicationTailerErrorHandler> errorHandler) {
        requireNonNull(errorHandler, "errorHandler cannot be null");
        return setErrorHandler(errorHandler.orElse(null));
    }

    /**
     * @param walMessageFilter the pre-decode WAL payload filter, or {@code null} to let the tailer fall back to the
     *                         decoding plugin's own default filter and then to a regex filter
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setWalMessageFilter(WalMessageFilter walMessageFilter) {
        this.walMessageFilter = walMessageFilter;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setWalMessageFilter(WalMessageFilter)}.
     *
     * @param walMessageFilter the filter, or empty to fall back
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcTailerDependenciesBuilder setWalMessageFilter(Optional<WalMessageFilter> walMessageFilter) {
        requireNonNull(walMessageFilter, "walMessageFilter cannot be null");
        return setWalMessageFilter(walMessageFilter.orElse(null));
    }

    /**
     * @param eventStreamTableNamesSupplier supplier of the event-stream table names, used to verify publication
     *                                      membership at stream start. {@code null} means "no tables known", which
     *                                      disables that diagnostic
     * @return this builder instance for fluent chaining
     */
    public CdcTailerDependenciesBuilder setEventStreamTableNamesSupplier(Supplier<Set<String>> eventStreamTableNamesSupplier) {
        this.eventStreamTableNamesSupplier = eventStreamTableNamesSupplier;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setEventStreamTableNamesSupplier(Supplier)}.
     *
     * @param eventStreamTableNamesSupplier the supplier, or empty for none
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcTailerDependenciesBuilder setEventStreamTableNamesSupplier(Optional<Supplier<Set<String>>> eventStreamTableNamesSupplier) {
        requireNonNull(eventStreamTableNamesSupplier, "eventStreamTableNamesSupplier cannot be null");
        return setEventStreamTableNamesSupplier(eventStreamTableNamesSupplier.orElse(null));
    }

    /**
     * Builds the dependencies, applying the neutral defaults for the collaborators that were not set.
     *
     * @return the dependencies
     */
    public CdcTailerDependencies build() {
        return new CdcTailerDependencies(requireNonNull(replicationDataSource, "replicationDataSource cannot be null"),
                                         requireNonNull(jdbi, "jdbi cannot be null"),
                                         requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null"),
                                         requireNonNull(logicalDecodingPlugin, "logicalDecodingPlugin cannot be null"),
                                         requireNonNull(availability, "availability cannot be null"),
                                         meterRegistry,
                                         errorHandler != null ? errorHandler : new DefaultWalReplicationTailerErrorHandler(),
                                         walMessageFilter,
                                         eventStreamTableNamesSupplier != null ? eventStreamTableNamesSupplier : Set::of);
    }
}
