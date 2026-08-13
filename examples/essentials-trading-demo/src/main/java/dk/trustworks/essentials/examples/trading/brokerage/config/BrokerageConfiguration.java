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

package dk.trustworks.essentials.examples.trading.brokerage.config;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradingAccountEvent;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;

import java.time.Clock;
import java.util.Optional;

/**
 * Spring wiring for the {@code brokerage} bounded context.
 *
 * <p>Only the {@link TradingAccount} closing-books chain is assembled here. {@code Trades} and {@code Settlements}
 * build their own {@link StatefulAggregateRepository} in their constructors, the way the house template's
 * {@code Accounts} does, so they need no bean of their own — a trading account needs six collaborating framework
 * objects to support generation rollover, which is more than a repository wrapper should assemble for itself.
 *
 * <p>The {@link AggregateType} constants that used to live in a module-wide configuration class now live on the
 * repository wrappers ({@link TradingAccounts#AGGREGATE_TYPE} and its two siblings), beside the aggregates whose
 * streams they name.
 */
@Configuration
@EnableConfigurationProperties(TradingAccountClosingBooksProperties.class)
public class BrokerageConfiguration {

    /**
     * Publishes the policies {@link TradingAccount} declares to the policy registries, which is what the admin API's
     * lifecycle endpoints report.
     * <p>
     * The framework only registers these from its two bean post-processors, so the annotations reach a registry only
     * when the annotated class is itself a Spring bean. An aggregate root is not — a singleton instance of one would be
     * meaningless — so the annotations on TradingAccount would otherwise be inert and the console would show no
     * policies. The annotation stays the single source of the values; this only carries them across.
     */
    @Bean
    public InitializingBean tradingAccountPolicyRegistrations(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                              AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry) {
        return () -> {
            var snapshotPolicy = AnnotationUtils.findAnnotation(TradingAccount.class,
                                                                AggregateSnapshotPolicy.class);
            if (snapshotPolicy != null) {
                snapshotPolicyRegistry.register(new AggregateSnapshotPolicyDescriptor(TradingAccount.class,
                                                                                      Optional.of(TradingAccounts.AGGREGATE_TYPE.toString()),
                                                                                      snapshotPolicy));
            }
            var closingBooksPolicy = AnnotationUtils.findAnnotation(TradingAccount.class,
                                                                    AggregateClosingBooksPolicy.class);
            if (closingBooksPolicy != null) {
                closingBooksPolicyRegistry.register(new AggregateClosingBooksPolicyDescriptor(TradingAccount.class,
                                                                                              Optional.of(TradingAccounts.AGGREGATE_TYPE.toString()),
                                                                                              closingBooksPolicy));
            }
        };
    }

    @Bean
    public PostgresqlClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository(
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        return new PostgresqlClosingBooksGenerationRepository<>(unitOfWorkFactory,
                                                                Optional.empty(),
                                                                logicalAggregateIdSerializer());
    }

    @Bean
    public TypedAggregateClosingBooksGenerationAccess<TradingAccountId> tradingAccountClosingBooksGenerationAccess(
            ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository) {
        return new TypedAggregateClosingBooksGenerationAccess<>() {
            @Override
            public AggregateType aggregateType() {
                return TradingAccounts.AGGREGATE_TYPE;
            }

            @Override
            public Class<?> aggregateImplementationType() {
                return TradingAccount.class;
            }

            @Override
            public ClosingBooksGenerationRepository<TradingAccountId> generationRepository() {
                return tradingAccountGenerationRepository;
            }

            @Override
            public ClosingBooksLogicalAggregateIdSerializer<TradingAccountId> logicalAggregateIdSerializer() {
                return BrokerageConfiguration.logicalAggregateIdSerializer();
            }
        };
    }

    @Bean
    public ClosingBooksCoordinator<TradingAccountId> tradingAccountClosingBooksCoordinator(
            ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            Optional<MeterRegistry> meterRegistry) {
        // TradingAccounts roll over via ON_ACCESS, which never runs a scheduled scan - the coordinator is the
        // only place the rollover can be measured, so the registry has to reach it for the admin UI's
        // closing-books statistics to show anything.
        return new ClosingBooksCoordinator<>(TradingAccounts.AGGREGATE_TYPE,
                                             tradingAccountGenerationRepository,
                                             // The <logicalId>#<generation> convention lives on the id type, which is
                                             // also what the account_statement view parses it back out with. It used to
                                             // be written here and re-derived there, with nothing tying the two together.
                                             (aggregateType, logicalAggregateId, generation) ->
                                                     TradingAccountGenerationId.of(logicalAggregateId.value(), generation).toString(),
                                             unitOfWorkFactory,
                                             Clock.systemUTC(),
                                             meterRegistry);
    }

    @Bean
    public StatefulAggregateRepository<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountStreamRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
            Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        return aggregateSnapshotRepositoryProvider
                .map(provider -> StatefulAggregateRepository.fromUsingSnapshotRepositoryProvider(
                        eventStore,
                        TradingAccounts.AGGREGATE_TYPE,
                        StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                        TradingAccount.class,
                        provider))
                .orElseGet(() -> StatefulAggregateRepository.from(
                        eventStore,
                        TradingAccounts.AGGREGATE_TYPE,
                        StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                        TradingAccount.class));
    }

    @Bean
    public ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountRepository(
            StatefulAggregateRepository<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountStreamRepository,
            ClosingBooksCoordinator<TradingAccountId> tradingAccountClosingBooksCoordinator) {
        return new ClosingBooksLogicalAggregateRepository<>(TradingAccounts.AGGREGATE_TYPE,
                                                           tradingAccountStreamRepository,
                                                           tradingAccountClosingBooksCoordinator,
                                                           new ClosingBooksStreamIdSerializer<>() {
                                                               @Override
                                                               public String serialize(TradingAccountGenerationId streamId) {
                                                                   return streamId.toString();
                                                               }

                                                               @Override
                                                               public TradingAccountGenerationId deserialize(String persistedStreamId) {
                                                                   return TradingAccountGenerationId.of(persistedStreamId);
                                                               }
                                                           });
    }

    /**
     * One definition, used by both the generation repository and the generation-access bean. These were two identical
     * anonymous classes before.
     */
    private static ClosingBooksLogicalAggregateIdSerializer<TradingAccountId> logicalAggregateIdSerializer() {
        return new ClosingBooksLogicalAggregateIdSerializer<>() {
            @Override
            public String serialize(LogicalAggregateId<TradingAccountId> logicalAggregateId) {
                return logicalAggregateId.value().toString();
            }

            @Override
            public LogicalAggregateId<TradingAccountId> deserialize(String persistedValue) {
                return new LogicalAggregateId<>(TradingAccountId.of(persistedValue));
            }
        };
    }
}
