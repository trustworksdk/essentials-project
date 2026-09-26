/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.examples.trading.market_data.use_cases.risk_approve_instrument;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Knobs for the stubbed risk service this slice calls. Registered by {@code MarketDataConfiguration}, because the slice
 * belongs to the {@code market_data} context and its context owns its own property registration.
 */
@ConfigurationProperties(prefix = "trading-demo.risk-approval")
public class RiskApprovalProperties {
    /**
     * How long the stubbed risk call blocks. This is the whole point of the slice, so it is configurable: raise it to
     * watch a message handler occupy a queue-consumer thread for that long while holding no database connection.
     * <p>
     * It must stay comfortably below {@code essentials.durable-queues.message-handling-timeout} (30s by
     * default). Past that timeout the in-flight message is treated as stuck and redelivered while the first attempt is
     * still blocked, which is why the aggregate's risk methods are idempotent.
     */
    private Duration     latency         = Duration.ofMillis(500);
    /**
     * Ticker symbols the stub refuses, so the rejection path can be demonstrated without a random outcome. Matched
     * case-insensitively; everything else is approved.
     */
    private List<String> rejectedSymbols = List.of();

    public Duration getLatency() {
        return latency;
    }

    public void setLatency(Duration latency) {
        this.latency = latency;
    }

    public List<String> getRejectedSymbols() {
        return rejectedSymbols;
    }

    public void setRejectedSymbols(List<String> rejectedSymbols) {
        this.rejectedSymbols = rejectedSymbols;
    }
}
