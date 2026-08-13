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

package dk.trustworks.essentials.examples.trading.market_data.config;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyDescriptor;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyRegistry;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrice;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Optional;

/**
 * Spring wiring for the {@code market_data} bounded context.
 *
 * <p>There is only one bean: both aggregates' repositories are built by their own wrappers
 * ({@code Instruments}, {@code InstrumentPrices}), the way the house template's {@code Accounts} does.
 */
@Configuration
public class MarketDataConfiguration {

    /**
     * Carries {@link InstrumentPrice}'s declared snapshot policy into the registry the admin console reads.
     * <p>
     * Same reason as the brokerage context's equivalent: the framework registers these from a bean post-processor, and
     * an aggregate root is not a Spring bean, so the annotation would otherwise be inert.
     * <p>
     * Note that this only publishes the policy to the console. What causes snapshots to actually be written and read
     * is {@code InstrumentPrices} building its repository through the {@code AggregateSnapshotRepositoryProvider} —
     * the two are separate wiring steps and registering here without doing that leaves the policy inert.
     */
    @Bean
    public InitializingBean instrumentPricePolicyRegistrations(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry) {
        return () -> {
            var snapshotPolicy = AnnotationUtils.findAnnotation(InstrumentPrice.class,
                                                                AggregateSnapshotPolicy.class);
            if (snapshotPolicy != null) {
                snapshotPolicyRegistry.register(new AggregateSnapshotPolicyDescriptor(InstrumentPrice.class,
                                                                                      Optional.of(InstrumentPrices.AGGREGATE_TYPE.toString()),
                                                                                      snapshotPolicy));
            }
        };
    }
}
