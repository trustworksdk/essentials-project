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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.archive.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksGenerationAccessProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Optional;

@AutoConfiguration(after = ClosingBooksConfiguration.class)
@ConditionalOnClass(AggregateArchiveApi.class)
@ConditionalOnProperty(prefix = "essentials.eventstore.archives", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EssentialsEventStoreProperties.class)
public class AggregateArchiveApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AggregateArchiveApiConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AggregateArchiveRegistry aggregateArchiveRegistry(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        return new PostgresqlAggregateArchiveRegistry(unitOfWorkFactory, Optional.empty());
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateArchiveApi aggregateArchiveApi(EssentialsSecurityProvider securityProvider,
                                                   AggregateArchiveRegistry aggregateArchiveRegistry) {
        return new DefaultAggregateArchiveApi(securityProvider, aggregateArchiveRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateArchiveStatisticsApi aggregateArchiveStatisticsApi(EssentialsSecurityProvider securityProvider,
                                                                       AggregateArchiveRegistry aggregateArchiveRegistry) {
        return new DefaultAggregateArchiveStatisticsApi(securityProvider, aggregateArchiveRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateArchiveExporter aggregateArchiveExporter(JSONEventSerializer jsonSerializer) {
        return new JacksonJsonLinesAggregateArchiveExporter(jsonSerializer);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateArchiveDestination aggregateArchiveDestination(EssentialsEventStoreProperties properties) {
        var configuredRoot = Path.of(properties.getArchives().getFilesystemRootDirectory());
        warnIfArchiveRootIsUnderTmpdir(configuredRoot);
        return new FileSystemAggregateArchiveDestination(configuredRoot);
    }

    private static void warnIfArchiveRootIsUnderTmpdir(Path configuredRoot) {
        var tmpdirProperty = System.getProperty("java.io.tmpdir");
        if (tmpdirProperty == null || tmpdirProperty.isBlank()) {
            return;
        }
        var tmpdir = Path.of(tmpdirProperty).toAbsolutePath().normalize();
        var resolvedRoot = configuredRoot.toAbsolutePath().normalize();
        if (resolvedRoot.startsWith(tmpdir)) {
            log.warn("Aggregate archive root directory '{}' is under the system temp directory '{}'. " +
                             "Archives stored here may be removed when the OS cleans up temp space (e.g. on reboot). " +
                             "Configure 'essentials.eventstore.archives.filesystem-root-directory' to a persistent path.",
                     resolvedRoot,
                     tmpdir);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateGenerationArchiver aggregateGenerationArchiver(AggregateArchiveRegistry aggregateArchiveRegistry,
                                                                   AggregateClosingBooksGenerationAccessProvider generationAccessProvider,
                                                                   ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                                   EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                   AggregateArchiveExporter aggregateArchiveExporter,
                                                                   AggregateArchiveDestination aggregateArchiveDestination,
                                                                   Optional<MeterRegistry> meterRegistryOptional) {
        return new DefaultAggregateGenerationArchiver(aggregateArchiveRegistry,
                                                      generationAccessProvider,
                                                      eventStore,
                                                      unitOfWorkFactory,
                                                      aggregateArchiveExporter,
                                                      aggregateArchiveDestination,
                                                      meterRegistryOptional);
    }
}
