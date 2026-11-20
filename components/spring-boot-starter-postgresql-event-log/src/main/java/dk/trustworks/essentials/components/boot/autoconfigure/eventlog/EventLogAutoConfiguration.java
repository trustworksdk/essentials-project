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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsProperties;
import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.SpringTransactionAwareEventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.causation.CausationCommandContextPropagator;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.CausationDurableQueuesInterceptor;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.reactive.*;
import dk.trustworks.essentials.reactive.command.AbstractCommandBus;
import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

/**
 * Spring Boot auto-configuration of the new event store engine ({@link EventLog}) and its {@link EventStore} adapter
 * ({@link EventLogEventStore}). <b>Preview</b>, and off by default: {@code essentials.event-log.enabled=true} turns it on.
 * <p>
 * With it on, {@link EventLogImportFilter} drops the old event-store starter's {@code EventStoreConfiguration} from the
 * auto-configurations, while its {@code SnapshotConfiguration}, {@code ClosingBooksConfiguration} and aggregate admin
 * configurations stay and run on the adapter. This configuration therefore declares what {@code EventStoreConfiguration}
 * would otherwise have declared for them - the {@link EventStoreUnitOfWorkFactory}, the {@link EventStoreEventBus}, the
 * {@link JSONEventSerializer}, the {@link PersistableEventMapper} and the causation beans - built the same way, and is
 * ordered before those configurations, whose beans are {@code @ConditionalOnBean} of them.
 * <p>
 * Ordered by name before {@code EssentialsComponentsConfiguration}, as the other starters are, so there is no compile-time
 * ordering dependency on the other auto-configurations.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class,
                   beforeName = {"dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration",
                                 // Their beans are @ConditionalOnBean(ConfigurableEventStore, EventStoreUnitOfWorkFactory, JSONEventSerializer)
                                 "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.SnapshotConfiguration",
                                 "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.ClosingBooksConfiguration"})
@ConditionalOnClass(EventLog.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "essentials.event-log", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({EssentialsEventLogProperties.class, EssentialsComponentsProperties.class})
public class EventLogAutoConfiguration {

    /**
     * ESS-138: fails start-up with a message naming every classic processor bean ({@code EventProcessor},
     * {@code ViewEventProcessor}, {@code InTransactionEventProcessor}) and what to port it to, before any bean is created.
     * {@code static}, as a {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor} bean must be.
     */
    @Bean
    static UnportedEventProcessorGuard unportedEventProcessorGuard() {
        return new UnportedEventProcessorGuard();
    }

    /**
     * Micrometer meters of the engine ({@code MicrometerEventLogObserver}: lag, failures, parks, lost leases, page time),
     * when Micrometer is on the class path and {@code essentials.event-log.metrics.enabled} is not {@code false}. The
     * registry is looked up when the {@link EventLog} is built, so no ordering against the metrics auto-configuration is
     * needed; without a registry the engine is left unmetered. An application {@link EventLogObserver} bean replaces it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(prefix = "essentials.event-log.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MicrometerMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "eventLogMicrometerMetrics")
        EventLogMetrics eventLogMicrometerMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries,
                                                  EssentialsComponentsProperties componentsProperties) {
            return () -> {
                var registry = registries.getIfAvailable();
                return registry == null ? null
                                        : new dk.trustworks.essentials.components.eventlog.observability.micrometer.MicrometerEventLogObserver(registry,
                                                                                                                                              componentsProperties.getTracingProperties().getModuleTag());
            };
        }
    }

    /**
     * The event store interceptors the old event-store starter's {@code EventStoreConfiguration} declares for tracing and
     * timing, under the same conditions and the same properties, since this starter's import filter drops that
     * configuration. {@link EventLogEventStore} runs them on every append and load; the tracing interceptor is also a
     * {@link PersistableEventEnricher}, so the {@code essentialsEventStore} bean picks it up and the current trace
     * context ({@code traceparent}) is stored in every appended event's metadata.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class EventStoreInterceptorsConfiguration {
        /**
         * As the old starter's: {@code essentials.eventstore.metrics.*} (enabled, thresholds) and the module tag.
         */
        @Bean
        @ConditionalOnMissingBean
        dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.RecordExecutionTimeEventStoreInterceptor measurementEventStoreInterceptor(
                Optional<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
                EssentialsComponentsProperties componentsProperties,
                org.springframework.core.env.Environment environment) {
            var metrics = org.springframework.boot.context.properties.bind.Binder.get(environment)
                                                                                 .bind("essentials.eventstore.metrics", EssentialsComponentsProperties.MetricsProperties.class)
                                                                                 .orElseGet(EssentialsComponentsProperties.MetricsProperties::new);
            var taker = metrics.isEnabled()
                        ? dk.trustworks.essentials.shared.measurement.MeasurementTaker.builder()
                                                                                      .setLoggingRecorder(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.RecordExecutionTimeEventStoreInterceptor.class,
                                                                                                          metrics.toLogThresholds())
                                                                                      .setMeterRegistry(meterRegistry)
                                                                                      .build()
                        : dk.trustworks.essentials.shared.measurement.MeasurementTaker.none();
            return new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.RecordExecutionTimeEventStoreInterceptor(
                    taker, componentsProperties.getTracingProperties().getModuleTag());
        }
    }

    /**
     * As the old starter's: with {@code management.tracing.enabled=true}, and {@code essentials.eventstore.verbose-tracing}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    @ConditionalOnProperty(prefix = "management.tracing", name = "enabled", havingValue = "true")
    static class TracingConfiguration {
        @Bean
        dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.MicrometerTracingEventStoreInterceptor micrometerTracingEventStoreInterceptor(
                Optional<io.micrometer.tracing.Tracer> tracer,
                Optional<io.micrometer.tracing.propagation.Propagator> propagator,
                Optional<io.micrometer.observation.ObservationRegistry> observationRegistry,
                org.springframework.core.env.Environment environment) {
            return new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.MicrometerTracingEventStoreInterceptor(
                    tracer.get(),
                    propagator.get(),
                    observationRegistry.get(),
                    environment.getProperty("essentials.eventstore.verbose-tracing", Boolean.class, false));
        }
    }

    /**
     * The engine, started here so that aggregate types can be registered with the adapter while the context is being
     * built. Its schema ownership follows {@code essentials.schema.mode}: {@code create} lets the engine create its own
     * schema at start, every other mode hands it to the schema harness through the {@link EventLogSchemaContributor}.
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public EventLog eventLog(DataSource dataSource,
                             EssentialsEventLogProperties properties,
                             EssentialsComponentsProperties componentsProperties,
                             ObjectProvider<EventLogSchemaContributor> schemaContributor,
                             ObjectProvider<SchemaApplier> schemaApplier,
                             ObjectProvider<EventLogObserver> observer,
                             ObjectProvider<EventLogMetrics> metrics) throws SQLException {
        var builder = EventLog.builder()
                              .setDataSource(dataSource)
                              .setSettings(properties.toSettings())
                              .setSchemaOwnership(componentsProperties.getSchema().getMode().schemaOwnership());
        schemaContributor.ifAvailable(builder::setSchemaContributor);
        // An application's own observer wins; else the Micrometer one when a registry exists (extend
        // MicrometerEventLogObserver to have both)
        var applicationObserver = observer.getIfAvailable();
        if (applicationObserver != null) {
            builder.setObserver(applicationObserver);
        } else {
            var metricsObserver = metrics.orderedStream().map(EventLogMetrics::observer).filter(Objects::nonNull).findFirst();
            metricsObserver.ifPresent(builder::setObserver);
        }
        var contributor = schemaContributor.getIfAvailable();
        var applier     = schemaApplier.getIfAvailable();
        if (componentsProperties.getSchema().getMode() == SchemaMode.VALIDATE && contributor != null && applier != null) {
            // Aggregate types register while the context is built, before the harness runs: validate the engine's fixed
            // schema now, so a missing one fails as a SchemaValidationException rather than as the first statement's error
            applier.apply(List.of(new SchemaChangeSet(contributor.moduleId(), contributor.order(), contributor.contribute(SchemaContext.empty()))));
        }
        var eventLog = builder.build();
        eventLog.start();
        return eventLog;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.event-log", name = "initialize-schema", havingValue = "true", matchIfMissing = true)
    public EventLogSchemaContributor eventLogSchemaContributor(DataSource dataSource, EssentialsComponentsProperties componentsProperties) {
        var contributor = new EventLogSchemaContributor(dataSource);
        // emit: an aggregate type's tables go into the script, never onto the database
        return componentsProperties.getSchema().getMode() == SchemaMode.EMIT ? contributor.scriptOnly() : contributor;
    }

    @Bean("essentialsEventStore")
    @ConditionalOnMissingBean(EventStore.class)
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> eventStore(EventLog eventLog,
                                                                                       EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                                       JSONEventSerializer jsonSerializer,
                                                                                       PersistableEventMapper eventMapper,
                                                                                       Optional<TenantSerializer<?>> tenantSerializer,
                                                                                       List<PersistableEventEnricher> enrichers,
                                                                                       EventStoreEventBus eventStoreEventBus,
                                                                                       List<EventStoreInterceptor> eventStoreInterceptors) {
        // Optional is idiomatic at the @Bean injection point and is unwrapped on the spot
        var eventStore = new EventLogEventStore(eventLog,
                                                unitOfWorkFactory,
                                                jsonSerializer,
                                                eventMapper,
                                                tenantSerializer.orElseGet(TenantSerializer.NoSupportForMultiTenancySerializer::new),
                                                enrichers,
                                                Optional.of(eventStoreEventBus));
        eventStore.addEventStoreInterceptors(eventStoreInterceptors);
        return eventStore;
    }

    /**
     * As the old event-store starter builds it: joins Spring-managed transactions ({@code @Transactional}).
     */
    @Bean
    @ConditionalOnMissingBean
    public EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> eventStoreUnitOfWorkFactory(Jdbi jdbi,
                                                                                                   PlatformTransactionManager transactionManager,
                                                                                                   EssentialsComponentsProperties properties) {
        var unitOfWorkFactory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
        unitOfWorkFactory.setTransactionIsolation(properties.getUnitOfWork().getTransactionIsolation());
        return unitOfWorkFactory;
    }

    @Bean("essentialsEventBus")
    @ConditionalOnMissingBean
    public EventStoreEventBus eventBus(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                       Optional<OnErrorHandler> onErrorHandler,
                                       EssentialsComponentsProperties properties) {
        var localEventBusBuilder = LocalEventBus.builder()
                                                .busName("EventStoreLocalBus")
                                                .overflowMaxRetries(properties.getReactive().getOverflowMaxRetries())
                                                .parallelThreads(properties.getReactive().getEventBusParallelThreads())
                                                .backpressureBufferSize(properties.getReactive().getEventBusBackpressureBufferSize())
                                                .queuedTaskCapFactor(properties.getReactive().getQueuedTaskCapFactor());
        // Optional is idiomatic at the @Bean injection point and is unwrapped on the spot
        onErrorHandler.ifPresent(localEventBusBuilder::onErrorHandler);
        return new EventStoreEventBus(localEventBusBuilder.build(), unitOfWorkFactory);
    }

    /**
     * Same mapping as the old event-store starter's default: aggregate type, aggregate id, event type and event order;
     * no correlation id and no tenant.
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistableEventMapper persistableEventMapper() {
        return (aggregateId, aggregateTypeConfiguration, event, eventOrder) ->
                PersistableEvent.builder()
                                .setEvent(event)
                                .setAggregateType(aggregateTypeConfiguration.aggregateType)
                                .setAggregateId(aggregateId)
                                .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                .setEventOrder(eventOrder)
                                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public JSONEventSerializer jsonSerializer(EssentialsComponentsProperties properties) {
        return new Jackson3JSONEventSerializer(EssentialsObjectMappers.createJackson3ObjectMapper(properties.isImmutableJacksonModuleEnabled()));
    }

    /**
     * The causation beans the old event-store starter declares, under the same {@code essentials.eventstore.causation.enabled}
     * (default on): the enricher records the causing event on every appended event, the queue interceptor and the command
     * context propagator carry the cause across queue and async command hand-offs.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.eventstore.causation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CausationPersistableEventEnricher causationPersistableEventEnricher() {
        return new CausationPersistableEventEnricher();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.eventstore.causation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CausationDurableQueuesInterceptor causationDurableQueuesInterceptor() {
        return new CausationDurableQueuesInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.eventstore.causation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CausationCommandContextPropagator causationCommandContextPropagator(List<AbstractCommandBus> commandBuses) {
        var propagator = new CausationCommandContextPropagator();
        commandBuses.forEach(commandBus -> commandBus.addContextPropagator(propagator));
        return propagator;
    }

    /**
     * The page transactions of in-transaction subscriptions ({@link ViewProjection}): Spring-managed when a
     * {@link PlatformTransactionManager} exists, so a projection's repository joins the page's transaction; else the
     * unit-of-work factory's.
     */
    @Bean
    @ConditionalOnMissingBean
    public PageTransactionSource pageTransactionSource(ObjectProvider<PlatformTransactionManager> transactionManager,
                                                       DataSource dataSource,
                                                       EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        var manager = transactionManager.getIfAvailable();
        return manager != null ? new SpringPageTransactions(manager, dataSource) : new UnitOfWorkPageTransactions(unitOfWorkFactory);
    }

    /**
     * Starts every {@link ViewProjection} and {@link EventReactor} bean once the context is refreshed and stops them
     * before the {@link EventLog} stops. Not in schema mode {@code emit}, where the application only writes the script.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventLogProcessorLifecycle eventLogProcessorLifecycle(EventLog eventLog,
                                                                 ObjectProvider<ViewProjection> viewProjections,
                                                                 ObjectProvider<EventReactor> eventReactors,
                                                                 EssentialsComponentsProperties properties) {
        return new EventLogProcessorLifecycle(eventLog, viewProjections, eventReactors, properties.getSchema().getMode() == SchemaMode.EMIT);
    }

    /**
     * What an {@link EventLogViewProcessor} / {@link EventLogReactor} / {@link EventLogInboxProcessor} subclass bean is built with. Those are foundation
     * {@code Lifecycle}s, started by the components starter's lifecycle manager (not in schema mode {@code emit}).
     */
    @Bean
    @ConditionalOnMissingBean
    public EventLogProcessorDependencies eventLogProcessorDependencies(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource pageTransactionSource) {
        return new EventLogProcessorDependencies(eventLog, serializer, pageTransactionSource);
    }
}
