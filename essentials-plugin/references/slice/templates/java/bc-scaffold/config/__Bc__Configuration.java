package {{packagePath}}.{{bc}}.config;

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event;
import {{packagePath}}.{{bc}}.routing.{{Aggregate}}Command;
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the {{Bc}} bounded context.
 *
 * Each command slice's Decider is registered as its OWN {@code @Bean}; the
 * {@code EventStreamDeciderAndAggregateTypeConfigurator} collects them via
 * {@code List<EventStreamDecider<?, ?>>} and routes commands by aggregate type. Adding a command
 * slice is adding one {@code @Bean} method — never touching another slice's files.
 *
 * This wiring is part of every slice's definition of done: an unregistered Decider compiles, passes
 * every unit test, and silently breaks every {@code @SpringBootTest}.
 *
 * NOTE: Java uses the {@code EventStream*} family; Kotlin uses
 * {@code kotlin.eventsourcing.AggregateTypeConfiguration} + {@code DeciderAndAggregateTypeConfigurator}.
 * They are different APIs — do not mix them.
 */
@Configuration
public class {{Bc}}Configuration {

    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("{{AggregateType}}");

    @Bean
    public EventStreamDeciderAndAggregateTypeConfigurator {{bc}}Configurator(
            ConfigurableEventStore<?> eventStore,
            CommandBus commandBus,
            List<EventStreamAggregateTypeConfiguration> configs,
            List<EventStreamDecider<?, ?>> deciders) {
        return new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, configs, deciders);
    }

    @Bean
    public EventStreamAggregateTypeConfiguration {{aggregate}}AggregateTypeConfiguration() {
        return new EventStreamAggregateTypeConfiguration(
                AGGREGATE_TYPE,
                {{Aggregate}}Id.class,
                AggregateIdSerializer.serializerFor({{Aggregate}}Id.class),
                new HandlesCommandsThatInheritFromCommandType({{Aggregate}}Command.class),
                cmd -> (({{Aggregate}}Command) cmd).id(),
                event -> (({{Aggregate}}Event) event).id()
        );
    }

    // One @Bean per command slice's Decider — never a shared god-Decider.
    // /essentials:add-slice appends a method here for each new command slice.
}
