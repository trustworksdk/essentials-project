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

package dk.trustworks.essentials.examples.trading.market_data.views.market_quotes;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * The market-quotes view and its synthetic feed. Registered by {@code MarketDataConfiguration}.
 */
@ConfigurationProperties(prefix = "trading-demo.market-data")
public class MarketQuotesProperties {
    /**
     * A quote not refreshed for this long is stale: shown as such, and never used to size a hold.
     */
    private Duration  staleAfter = Duration.ofSeconds(10);
    private Synthetic synthetic  = new Synthetic();

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public Synthetic getSynthetic() {
        return synthetic;
    }

    public void setSynthetic(Synthetic synthetic) {
        this.synthetic = synthetic;
    }

    /**
     * A random walk per instrument, quoted around a mid price, until the exchange's stream exists.
     */
    public static class Synthetic {
        private boolean                 enabled          = true;
        /**
         * Instrument id to starting mid price, in {@code currency}.
         */
        private Map<String, BigDecimal> instruments      = new LinkedHashMap<>(Map.of("ACME", new BigDecimal("100.00")));
        private String                  currency         = "USD";
        private Duration                tickInterval     = Duration.ofSeconds(1);
        /**
         * Each tick moves the mid by up to this fraction, either way. {@code 0} keeps every quote where it started,
         * which is what tests want.
         */
        private BigDecimal              volatility       = new BigDecimal("0.001");
        /**
         * Half the bid/ask spread, in basis points of the mid.
         */
        private int                     halfSpreadBps    = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, BigDecimal> getInstruments() {
            return instruments;
        }

        public void setInstruments(Map<String, BigDecimal> instruments) {
            this.instruments = instruments;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public Duration getTickInterval() {
            return tickInterval;
        }

        public void setTickInterval(Duration tickInterval) {
            this.tickInterval = tickInterval;
        }

        public BigDecimal getVolatility() {
            return volatility;
        }

        public void setVolatility(BigDecimal volatility) {
            this.volatility = volatility;
        }

        public int getHalfSpreadBps() {
            return halfSpreadBps;
        }

        public void setHalfSpreadBps(int halfSpreadBps) {
            this.halfSpreadBps = halfSpreadBps;
        }
    }
}
