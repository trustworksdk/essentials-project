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

package dk.trustworks.essentials.examples.trading.orders.use_cases.place_order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Order entry's knobs. Registered by {@code OrdersConfiguration}.
 */
@ConfigurationProperties(prefix = "trading-demo.orders")
public class PlaceOrderProperties {
    /**
     * A market buy holds quantity x best ask x (1 + collar) (design Q-5): room for the price to move between the hold
     * and the fill. A fill above it is still booked — an execution is a fact — and may restrict the account.
     */
    private BigDecimal marketOrderCollar = new BigDecimal("0.05");

    public BigDecimal getMarketOrderCollar() {
        return marketOrderCollar;
    }

    public void setMarketOrderCollar(BigDecimal marketOrderCollar) {
        this.marketOrderCollar = marketOrderCollar;
    }
}
