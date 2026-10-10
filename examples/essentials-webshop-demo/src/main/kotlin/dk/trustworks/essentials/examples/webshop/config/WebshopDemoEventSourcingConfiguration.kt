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

package dk.trustworks.essentials.examples.webshop.config

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The one place where deciders meet the event store.
 *
 * [DeciderAndAggregateTypeConfigurator] collects **every** [AggregateTypeConfiguration] bean and **every**
 * [Decider] bean in the application, matches each decider to the aggregate type whose
 * `deciderSupportsAggregateTypeChecker` claims it, registers the aggregate type with the event store, and
 * subscribes an adapter on the [CommandBus] for each decider. That adapter is what turns
 * `commandBus.send(cmd)` into: resolve the aggregate id from the command, load that stream, call
 * `decider.handle(cmd, events)`, and append the returned event - all inside the UnitOfWork the command bus owns.
 *
 * This is deliberately application-level and declared exactly once: each bounded context contributes its own
 * `AggregateTypeConfiguration` beans (see `sales/config/SalesAggregateTypes`), and a second configurator would
 * register every decider twice.
 *
 * A decider needs no wiring of its own beyond `@Service`. There is no command-handler registration to forget.
 */
@Configuration
class WebshopDemoEventSourcingConfiguration {

    @Bean
    fun deciderAndAggregateTypeConfigurator(
        eventStore: ConfigurableEventStore<*>,
        commandBus: CommandBus,
        aggregateTypeConfigurations: List<AggregateTypeConfiguration>,
        deciders: List<Decider<*, *>>
    ): DeciderAndAggregateTypeConfigurator =
        DeciderAndAggregateTypeConfigurator(eventStore, commandBus, aggregateTypeConfigurations, deciders)
}
