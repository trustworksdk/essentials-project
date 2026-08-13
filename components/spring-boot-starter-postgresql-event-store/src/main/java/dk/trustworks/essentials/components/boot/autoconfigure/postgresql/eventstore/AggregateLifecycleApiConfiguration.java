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
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

import java.util.Optional;
import java.util.stream.Stream;

@AutoConfiguration(after = ClosingBooksConfiguration.class)
@ConditionalOnClass(AggregateLifecycleApi.class)
public class AggregateLifecycleApiConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public AggregateLifecycleApi aggregateLifecycleApi(EssentialsSecurityProvider securityProvider,
                                                       AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                       AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                                       ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                       JSONEventSerializer jsonSerializer,
                                                       Optional<AggregateClosingBooksGenerationAccessProvider> closingBooksGenerationAccessProvider,
                                                       Optional<AggregateSnapshotStore> snapshotStore) {
        return new DefaultAggregateLifecycleApi(securityProvider,
                                                snapshotPolicyRegistry,
                                                closingBooksPolicyRegistry,
                                                closingBooksGenerationAccessProvider,
                                                snapshotStore,
                                                eventStore,
                                                jsonSerializer);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateLifecycleStatisticsApi aggregateLifecycleStatisticsApi(EssentialsSecurityProvider securityProvider,
                                                                           AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                                           AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                                                           Optional<MeterRegistry> meterRegistry) {
        return new DefaultAggregateLifecycleStatisticsApi(securityProvider,
                                                          snapshotPolicyRegistry,
                                                          closingBooksPolicyRegistry,
                                                          meterRegistry);
    }

    /**
     * Collects generation access from both sources: accessors registered directly, and the ones
     * {@link ClosingBooksSetup#generationAccess() derived} from every {@link ClosingBooksSetup} bean - so an application
     * that uses the builder gets working admin-API lifecycle endpoints without also hand-writing an accessor.
     *
     * @param accessors directly registered accessors
     * @param setups    every {@link ClosingBooksSetup} bean in the context
     * @return the provider over both sets
     */
    @Bean
    @ConditionalOnMissingBean
    public AggregateClosingBooksGenerationAccessProvider aggregateClosingBooksGenerationAccessProvider(
            org.springframework.beans.factory.ObjectProvider<TypedAggregateClosingBooksGenerationAccess<?>> accessors,
            org.springframework.beans.factory.ObjectProvider<ClosingBooksSetup<?, ?>> setups) {
        var allAccessors = Stream.<TypedAggregateClosingBooksGenerationAccess<?>>concat(accessors.orderedStream(),
                                                                                       setups.orderedStream().map(ClosingBooksSetup::generationAccess))
                                 .toList();
        return new CachingAggregateClosingBooksGenerationAccessProvider(allAccessors);
    }
}
