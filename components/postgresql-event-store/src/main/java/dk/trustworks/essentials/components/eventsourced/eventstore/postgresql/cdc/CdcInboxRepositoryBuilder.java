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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link CdcInboxRepository}, obtained from {@link CdcInboxRepository#builder()}.
 * <p>
 * The {@link MeterRegistry} is held as a plain nullable field — absent means no metrics are recorded — and also has an
 * {@code Optional} overload, for Spring {@code @Bean} methods where an {@code Optional} injection point is idiomatic.
 */
public final class CdcInboxRepositoryBuilder {
    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private MeterRegistry                                                meterRegistry;
    private String                                                       cdcInboxTableName = CdcSql.DEFAULT_CDC_TABLE_NAME;

    /**
     * @param unitOfWorkFactory the {@link HandleAwareUnitOfWorkFactory} needed to access the database. Required
     * @return this builder instance for fluent chaining
     */
    public CdcInboxRepositoryBuilder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param meterRegistry the Micrometer registry, or {@code null} for no inbox metrics
     * @return this builder instance for fluent chaining
     */
    public CdcInboxRepositoryBuilder setMeterRegistry(MeterRegistry meterRegistry) {
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
    public CdcInboxRepositoryBuilder setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
        requireNonNull(meterRegistry, "meterRegistry cannot be null");
        return setMeterRegistry(meterRegistry.orElse(null));
    }

    /**
     * @param cdcInboxTableName the name of the CDC inbox table. Defaults to {@link CdcSql#DEFAULT_CDC_TABLE_NAME}.<br>
     *                          <strong>Security Note:</strong> the name is used directly in constructing SQL statements
     *                          through string concatenation. {@link CdcSql} validates it via
     *                          {@code PostgresqlUtil.checkIsValidTableOrColumnName} as a first line of defense, which is
     *                          not exhaustive — only derive this value from a controlled and trusted source
     * @return this builder instance for fluent chaining
     */
    public CdcInboxRepositoryBuilder setCdcInboxTableName(String cdcInboxTableName) {
        this.cdcInboxTableName = cdcInboxTableName;
        return this;
    }

    /**
     * Builds the repository.
     *
     * @return the repository
     */
    @SuppressWarnings("removal")
    public CdcInboxRepository build() {
        return new CdcInboxRepository(requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null"),
                                      Optional.ofNullable(meterRegistry),
                                      requireNonNull(cdcInboxTableName, "cdcInboxTableName cannot be null"));
    }
}
