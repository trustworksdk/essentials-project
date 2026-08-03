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

import dk.trustworks.essentials.components.eventsourced.aggregates.decider.AggregateIdResolver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingBooksAggregateIdResolversTest {
    @Test
    void resolves_the_current_stream_aggregate_id_from_the_logical_aggregate_id() {
        var aggregateType = AggregateType.of("Accounts");
        var generationResolver = new InMemoryClosingBooksGenerationResolver<String>();
        generationResolver.openNextGeneration(aggregateType,
                                             new LogicalAggregateId<>("Account-123"),
                                             (type, id, generation) -> "Account-123#" + generation);

        AggregateIdResolver<String, String> logicalAggregateIdResolver = command -> java.util.Optional.of(command);
        var streamAggregateIdResolver = ClosingBooksAggregateIdResolvers.resolveCurrentStreamAggregateId(aggregateType,
                                                                                                         logicalAggregateIdResolver,
                                                                                                         generationResolver);

        // Generation 1, hence the stream id the generator produced for it. This used to assert an unrelated "#7",
        // which the API no longer allows: the repository supplies the number the stream id is built from.
        assertThat(streamAggregateIdResolver.resolveFrom("Account-123")).contains("Account-123#1");
        assertThat(streamAggregateIdResolver.resolveFrom("Unknown")).isEmpty();
    }
}
