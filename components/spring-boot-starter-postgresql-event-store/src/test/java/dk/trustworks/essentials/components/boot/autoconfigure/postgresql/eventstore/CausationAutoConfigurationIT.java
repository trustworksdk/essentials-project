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

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The starter records event causation by default, turns it off with one property, and never overrides a cause set by
 * an application's own {@link PersistableEventMapper}.
 */
@Testcontainers
class CausationAutoConfigurationIT {
    private static final EventId CAUSE = EventId.of("the-cause");

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("causation-autoconfig-it")
            .withUsername("test-user")
            .withPassword("secret-password");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            EssentialsComponentsConfiguration.class,
                            EventStoreConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                            "essentials.life-cycles.start-life-cycles=false"
                    ).applyTo(ctx.getEnvironment()))
                    .withPropertyValues("essentials.eventstore.cdc.enabled=false");

    @Test
    void causation_is_recorded_by_default() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CausationPersistableEventEnricher.class);
            assertThat(ctx.getBean(EssentialsEventStoreProperties.class).getCausation().isEnabled()).isTrue();

            assertThat(appendWith(ctx, Optional.of(CAUSE))).contains(CAUSE);
        });
    }

    @Test
    void an_event_appended_with_no_cause_bound_has_no_cause() {
        contextRunner.run(ctx -> assertThat(appendWith(ctx, Optional.empty())).isEmpty());
    }

    @Test
    void causation_can_be_turned_off() {
        contextRunner.withPropertyValues("essentials.eventstore.causation.enabled=false")
                     .run(ctx -> {
                         assertThat(ctx).doesNotHaveBean(CausationPersistableEventEnricher.class);

                         assertThat(appendWith(ctx, Optional.of(CAUSE))).isEmpty();
                     });
    }

    @Test
    void a_cause_set_by_the_applications_own_mapper_is_kept() {
        var mappersCause = EventId.of("mappers-cause");
        contextRunner.withBean(PersistableEventMapper.class, () -> (aggregateId, configuration, event, eventOrder) ->
                             PersistableEvent.builder()
                                             .setEvent(event)
                                             .setAggregateType(configuration.aggregateType)
                                             .setAggregateId(aggregateId)
                                             .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                             .setEventOrder(eventOrder)
                                             .setCausedByEventId(mappersCause)
                                             .build())
                     .run(ctx -> assertThat(appendWith(ctx, Optional.of(CAUSE))).contains(mappersCause));
    }

    /**
     * Append one event with the given cause bound (or explicitly none) and read back the cause it was persisted with
     */
    @SuppressWarnings("unchecked")
    private static Optional<EventId> appendWith(ApplicationContext ctx, Optional<EventId> cause) {
        var eventStore        = (ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration>) ctx.getBean(ConfigurableEventStore.class);
        var unitOfWorkFactory = (EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>) ctx.getBean(EventStoreUnitOfWorkFactory.class);
        var aggregateType     = AggregateType.of("Causation" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        eventStore.addAggregateEventStreamConfiguration(aggregateType, AggregateIdSerializer.serializerFor(String.class));

        var eventId = CausationContext.where(cause)
                                      .call(() -> unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType, "id-1", new NotifyPollingAutoConfigurationIT.TestEvent("first"))
                                                                                                   .eventList()
                                                                                                   .getFirst()
                                                                                                   .eventId()));
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEvent(aggregateType, eventId)
                                                                .orElseThrow()
                                                                .causedByEventId());
    }
}
