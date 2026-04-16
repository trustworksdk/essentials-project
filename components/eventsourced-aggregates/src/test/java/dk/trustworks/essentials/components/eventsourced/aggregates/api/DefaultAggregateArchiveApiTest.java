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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.archive.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultAggregateArchiveApiTest {
    @Test
    void it_exposes_archived_generations() {
        var registry = mock(AggregateArchiveRegistry.class);
        var entry = new AggregateArchiveEntry(AggregateType.of("Orders"),
                                              "order-1",
                                              1L,
                                              "order-1#1",
                                              AggregateArchiveStatus.ARCHIVED,
                                              AggregateArchiveFormat.PARQUET,
                                              "s3://bucket/orders/order-1/1.parquet",
                                              42L,
                                              "abc123",
                                              OffsetDateTime.parse("2026-04-01T00:00:00Z"),
                                              OffsetDateTime.parse("2026-04-10T00:00:00Z"),
                                              null);
        when(registry.findArchivedGeneration(AggregateType.of("Orders"), "order-1", 1L)).thenReturn(Optional.of(entry));
        when(registry.findArchivedGenerations(AggregateType.of("Orders"), "order-1")).thenReturn(List.of(entry));

        var api = new DefaultAggregateArchiveApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(), registry);

        assertThat(api.findArchivedGeneration("principal", AggregateType.of("Orders"), "order-1", 1L))
                .hasValueSatisfying(archivedGeneration -> {
                    assertThat(archivedGeneration.aggregateType()).isEqualTo("Orders");
                    assertThat(archivedGeneration.streamAggregateId()).isEqualTo("order-1#1");
                    assertThat(archivedGeneration.format()).isEqualTo("PARQUET");
                });
        assertThat(api.findArchivedGenerations("principal", AggregateType.of("Orders"), "order-1"))
                .singleElement()
                .satisfies(archivedGeneration -> assertThat(archivedGeneration.archiveLocation()).contains("s3://bucket"));
    }
}
