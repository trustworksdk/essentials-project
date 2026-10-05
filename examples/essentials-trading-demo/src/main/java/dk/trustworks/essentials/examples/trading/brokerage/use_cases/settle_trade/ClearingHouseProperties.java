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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.settle_trade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Knobs for the stubbed clearing house the {@code brokerage.settle_trade} automation calls. Registered by
 * {@code BrokerageConfiguration}, because the slice belongs to the {@code brokerage} context.
 */
@ConfigurationProperties(prefix = "trading-demo.clearing-house")
public class ClearingHouseProperties {
    /**
     * How long the stubbed clearing confirmation blocks. Like the risk service's latency, it must stay well below
     * {@code essentials.durable-queues.message-handling-timeout} (30s by default), or the message is redelivered while
     * the first attempt is still blocked. Kept below the {@code observability} profile's 200ms INFO threshold for message
     * handling, which every trade's clearing step would otherwise cross - two INFO lines per trade.
     */
    private Duration latency = Duration.ofMillis(100);

    public Duration getLatency() {
        return latency;
    }

    public void setLatency(Duration latency) {
        this.latency = latency;
    }
}
