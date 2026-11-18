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

package dk.trustworks.essentials.examples.trading.orders.config;

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.examples.trading.orders.aggregates.*;
import dk.trustworks.essentials.examples.trading.orders.external_systems.exchange.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.place_order.PlaceOrderProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties({StubExchangeProperties.class, ExchangeSubmissionProperties.class, PlaceOrderProperties.class, OrderProcessingProperties.class, OrderForwardingProperties.class})
public class OrdersConfiguration {

    @Bean
    public EssentialsAggregateDeclarations ordersAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                              .declare(Orders.AGGREGATE_TYPE, Order.class)
                                              .declare(UnknownExchangeOrders.AGGREGATE_TYPE, UnknownExchangeOrder.class)
                                              .build();
    }
}
