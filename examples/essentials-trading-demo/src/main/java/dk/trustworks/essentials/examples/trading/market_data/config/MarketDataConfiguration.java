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

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instrument;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrice;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.risk_approve_instrument.RiskApprovalProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the {@code market_data} bounded context.
 *
 * <p>There is only one bean: both aggregates' repositories are built by their own wrappers
 * ({@code Instruments}, {@code InstrumentPrices}), the way the house template's {@code Accounts} does.
 *
 * <p>{@link RiskApprovalProperties} is registered here rather than on the application class, because it belongs to a
 * slice of this context — the same split as {@code brokerage}'s closing-books properties.
 */
@Configuration
@EnableConfigurationProperties(RiskApprovalProperties.class)
public class MarketDataConfiguration {

    /**
     * Declares this context's aggregates, which is what carries {@link InstrumentPrice}'s declared snapshot policy into
     * the registry the admin console reads.
     * <p>
     * Same reason as the brokerage context's equivalent: the framework registers these annotations from a bean
     * post-processor, and an aggregate root is not a Spring bean, so without a declaration the annotation is inert and
     * nothing says so. Each context declares its own aggregates; the declarations from every context are merged.
     * <p>
     * Note that declaring only publishes the policy. What causes snapshots to actually be written and read is
     * {@code InstrumentPrices} building its repository through the {@code AggregateSnapshotRepositoryProvider} — the two
     * are separate wiring steps, and declaring without doing that leaves the policy inert.
     */
    @Bean
    public EssentialsAggregateDeclarations marketDataAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                            .declare(InstrumentPrices.AGGREGATE_TYPE, InstrumentPrice.class)
                                            .declare(Instruments.AGGREGATE_TYPE, Instrument.class)
                                            .build();
    }
}
