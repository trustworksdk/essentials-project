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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler.WalReplicationTailerErrorHandler;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The collaborators a {@link WalReplicationTailer} runs with, as opposed to the configuration it runs under
 * ({@link CdcTailerSettings}) and where it sends what it reads ({@link CdcDelivery}).
 * <p>
 * Four of these were previously {@code Optional} constructor parameters that the tailer immediately resolved to a
 * neutral default or a nullable field. They are plain nullable fields on the builder instead, resolved once in
 * {@link CdcTailerDependenciesBuilder#build()}, which is why nothing here is an {@code Optional}.
 */
public final class CdcTailerDependencies {
    private final DataSource                                                   replicationDataSource;
    private final Jdbi                                                         jdbi;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final LogicalDecodingPlugin                                        logicalDecodingPlugin;
    private final CdcAvailability                                              availability;
    private final MeterRegistry                                                meterRegistry;
    private final WalReplicationTailerErrorHandler                             errorHandler;
    private final WalMessageFilter                                             walMessageFilter;
    private final Supplier<Set<String>>                                        eventStreamTableNamesSupplier;

    CdcTailerDependencies(DataSource replicationDataSource,
                          Jdbi jdbi,
                          HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                          LogicalDecodingPlugin logicalDecodingPlugin,
                          CdcAvailability availability,
                          MeterRegistry meterRegistry,
                          WalReplicationTailerErrorHandler errorHandler,
                          WalMessageFilter walMessageFilter,
                          Supplier<Set<String>> eventStreamTableNamesSupplier) {
        this.replicationDataSource = replicationDataSource;
        this.jdbi = jdbi;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.logicalDecodingPlugin = logicalDecodingPlugin;
        this.availability = availability;
        this.meterRegistry = meterRegistry;
        this.errorHandler = errorHandler;
        this.walMessageFilter = walMessageFilter;
        this.eventStreamTableNamesSupplier = eventStreamTableNamesSupplier;
    }

    /**
     * @return a new builder
     */
    public static CdcTailerDependenciesBuilder builder() {
        return new CdcTailerDependenciesBuilder();
    }

    /** @return the replication-enabled {@link DataSource} the tailer opens its slot on. Required */
    public DataSource replicationDataSource() {
        return replicationDataSource;
    }

    /** @return the {@link Jdbi} instance. Required */
    public Jdbi jdbi() {
        return jdbi;
    }

    /** @return the unit-of-work factory used for inbox writes and slot bookkeeping. Required */
    public HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory() {
        return unitOfWorkFactory;
    }

    /** @return the plugin that decodes raw WAL payloads. Required */
    public LogicalDecodingPlugin logicalDecodingPlugin() {
        return logicalDecodingPlugin;
    }

    /** @return the shared CDC availability tracker. Required */
    public CdcAvailability availability() {
        return availability;
    }

    /** @return the Micrometer registry, or {@code null} for no tailer metrics */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /** @return the replication error handler. Never {@code null} — defaults to {@link DefaultWalReplicationTailerErrorHandler} */
    public WalReplicationTailerErrorHandler errorHandler() {
        return errorHandler;
    }

    /**
     * @return the pre-decode WAL payload filter, or {@code null} to let the tailer fall back to the plugin's own
     *         default filter and then to a {@code RegexWalMessageFilter}. Resolving that chain needs the
     *         {@link #eventStreamTableNamesSupplier()}, so it stays in the tailer rather than in the builder
     */
    public WalMessageFilter walMessageFilter() {
        return walMessageFilter;
    }

    /** @return supplier of the event-stream table names, used for publication-membership diagnostics. Never {@code null} */
    public Supplier<Set<String>> eventStreamTableNamesSupplier() {
        return eventStreamTableNamesSupplier;
    }
}
