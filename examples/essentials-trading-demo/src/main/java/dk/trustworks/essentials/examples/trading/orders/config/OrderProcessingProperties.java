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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many threads consume each of the order flow's inboxes, on the one instance that holds each inbox's lock. Every
 * inbox keeps one order's messages in order whatever the count ({@code SingleGlobalConsumer}); more threads handle
 * different orders at once. Registered by {@code OrdersConfiguration}.
 * <p>
 * The defaults are the processors' own. The order-flow measurement (EF-2 M5, {@code docs/key-partitioned-event-processing.md}
 * §5) varies them.
 */
@ConfigurationProperties(prefix = "trading-demo.orders.consumers")
public class OrderProcessingProperties {
    /**
     * {@code Inbox:ExchangeOrderRouter}: submissions and cancels at the exchange.
     */
    private int exchangeRouter  = 1;
    /**
     * {@code Inbox:OrderLedgerProcessor}: bookings and releases on the account. Orders of one account collide on its
     * stream; the loser is redelivered.
     */
    private int ledger          = 1;
    /**
     * {@code Inbox:Orders:ExchangeReports}: the exchange's reports, recorded on the orders.
     */
    private int exchangeReports = 2;

    public int getExchangeRouter() {
        return exchangeRouter;
    }

    public void setExchangeRouter(int exchangeRouter) {
        this.exchangeRouter = exchangeRouter;
    }

    public int getLedger() {
        return ledger;
    }

    public void setLedger(int ledger) {
        this.ledger = ledger;
    }

    public int getExchangeReports() {
        return exchangeReports;
    }

    public void setExchangeReports(int exchangeReports) {
        this.exchangeReports = exchangeReports;
    }
}
