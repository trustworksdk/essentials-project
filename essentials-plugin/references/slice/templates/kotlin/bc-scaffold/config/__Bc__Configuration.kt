package {{packagePath}}.{{bc}}.config

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event
import {{packagePath}}.{{bc}}.routing.{{Aggregate}}Command
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the {{Bc}} bounded context.
 *
 * Each command slice's Decider is registered as its OWN `@Bean`; the
 * `DeciderAndAggregateTypeConfigurator` collects them via `List<Decider<*, *>>` and routes commands
 * by aggregate type. Adding a command slice is adding one `@Bean` line — never touching another
 * slice's files.
 *
 * This wiring is part of every slice's definition of done: an unregistered Decider compiles, passes
 * every unit test, and silently breaks every `@SpringBootTest`.
 */
@Configuration
class {{Bc}}Configuration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE: AggregateType = AggregateType.of("{{AggregateType}}")
    }

    @Bean
    fun deciderAndAggregateTypeConfigurator(
        eventStore: ConfigurableEventStore<*>,
        commandBus: CommandBus,
        aggregateTypeConfigurations: List<AggregateTypeConfiguration>,
        deciders: List<Decider<*, *>>
    ) = DeciderAndAggregateTypeConfigurator(eventStore, commandBus, aggregateTypeConfigurations, deciders)

    @Bean
    fun {{aggregate}}AggregateTypeConfiguration() = AggregateTypeConfiguration(
        aggregateType = AGGREGATE_TYPE,
        aggregateIdType = {{Aggregate}}Id::class.java,
        aggregateIdSerializer = AggregateIdSerializer.serializerFor({{Aggregate}}Id::class.java),
        deciderSupportsAggregateTypeChecker =
            DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType({{Aggregate}}Command::class),
        commandAggregateIdResolver = { cmd -> (cmd as {{Aggregate}}Command).id },
        eventAggregateIdResolver = { e -> (e as {{Aggregate}}Event).id }
    )

    // One @Bean per command slice's Decider — never a shared god-Decider.
    // /essentials:add-slice appends a line here for each new command slice.
}
