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
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.risk_approve_instrument.RiskApprovalProperties;
import dk.trustworks.essentials.examples.trading.market_data.views.market_quotes.MarketQuotesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the {@code market_data} bounded context.
 *
 * <p>{@link RiskApprovalProperties} and {@link MarketQuotesProperties} are registered here rather than on the
 * application class, because they belong to slices of this context.
 */
@Configuration
@EnableConfigurationProperties({RiskApprovalProperties.class, MarketQuotesProperties.class})
public class MarketDataConfiguration {

    /**
     * Declares this context's aggregate, so the admin console's aggregate registry lists it. The framework registers
     * policy annotations from a bean post-processor, and an aggregate root is not a Spring bean, so without a
     * declaration nothing would; the declarations from every context are merged.
     */
    @Bean
    public EssentialsAggregateDeclarations marketDataAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                            .declare(Instruments.AGGREGATE_TYPE, Instrument.class)
                                            .build();
    }
}
