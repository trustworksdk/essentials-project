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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.aggregates.decider.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Utility class containing command handler creation logic for handling "Closing Books" commands.
 * <p>
 * This class is designed to provide a specialized implementation of a command handler for use with
 * "Closing Books" operations. It works with decider-based command handling, leveraging configurations
 * like event stores, aggregate types, aggregate ID resolvers, and aggregate snapshot repositories.
 * <p>
 * The command handler created by this class utilizes a decider, which encapsulates the business logic
 * for transitioning the aggregate's state based on incoming commands and existing state.
 */
public final class ClosingBooksCommandHandlers {
    private ClosingBooksCommandHandlers() {
    }

    /**
     * Creates a decider-based command handler for managing operations on aggregates
     * by evaluating provided commands and deciding resulting events or errors.
     *
     * @param <CONFIG>                  The configuration type for the event store.
     * @param <LOGICAL_ID>              The logical identifier type for aggregates.
     * @param <COMMAND>                 The type of command to be processed.
     * @param <EVENT>                   The type of event to be generated.
     * @param <ERROR>                   The type of error that may occur during command handling.
     * @param <STATE>                   The type of the aggregate state.
     * @param eventStore                The event store that provides access to aggregate event streams.
     * @param aggregateType             The aggregate type for which commands will be handled.
     * @param logicalAggregateIdFromCommandResolver A resolver that extracts a logical aggregate ID from a command.
     * @param streamAggregateIdFromEventResolver    A resolver that extracts a stream aggregate ID from an event.
     * @param generationResolver         A resolver for determining the generation of aggregates based on logical IDs.
     * @param aggregateSnapshotRepository A repository for handling aggregate snapshots.
     * @param stateType                  The class type of the aggregate state.
     * @param decider                    The decider that determines the outcome of a command in terms of events or errors.
     * @return                           A command handler capable of processing commands and generating corresponding events or errors.
     */
    public static <CONFIG extends AggregateEventStreamConfiguration,
            LOGICAL_ID,
            COMMAND,
            EVENT,
            ERROR,
            STATE> CommandHandler<COMMAND, EVENT, ERROR> deciderBasedCommandHandler(ConfigurableEventStore<CONFIG> eventStore,
                                                                                   AggregateType aggregateType,
                                                                                   AggregateIdResolver<COMMAND, LOGICAL_ID> logicalAggregateIdFromCommandResolver,
                                                                                   AggregateIdResolver<EVENT, String> streamAggregateIdFromEventResolver,
                                                                                   ClosingBooksGenerationResolver<LOGICAL_ID> generationResolver,
                                                                                   AggregateSnapshotRepository aggregateSnapshotRepository,
                                                                                   Class<STATE> stateType,
                                                                                   Decider<COMMAND, EVENT, ERROR, STATE> decider) {
        requireNonNull(generationResolver, "No generationResolver provided");
        return CommandHandler.deciderBasedCommandHandler(eventStore,
                                                         aggregateType,
                                                         String.class,
                                                         ClosingBooksAggregateIdResolvers.resolveCurrentStreamAggregateId(aggregateType,
                                                                                                                          logicalAggregateIdFromCommandResolver,
                                                                                                                          generationResolver),
                                                         streamAggregateIdFromEventResolver,
                                                         aggregateSnapshotRepository,
                                                         stateType,
                                                         decider);
    }
}
