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

package dk.trustworks.essentials.examples.trading.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Runtime load-generator settings used after bootstrap has completed.
 */
@ConfigurationProperties(prefix = "trading-demo.load")
public class TradingDemoLoadGeneratorProperties {
    private boolean enabled = false;
    private Duration tradeInterval = Duration.ofSeconds(2);
    private Duration priceUpdateInterval = Duration.ofSeconds(1);
    private int maxGeneratedTrades = 500;
    private BigDecimalRange priceJitter = new BigDecimalRange(3, 9);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTradeInterval() {
        return tradeInterval;
    }

    public void setTradeInterval(Duration tradeInterval) {
        this.tradeInterval = tradeInterval;
    }

    public Duration getPriceUpdateInterval() {
        return priceUpdateInterval;
    }

    public void setPriceUpdateInterval(Duration priceUpdateInterval) {
        this.priceUpdateInterval = priceUpdateInterval;
    }

    public int getMaxGeneratedTrades() {
        return maxGeneratedTrades;
    }

    public void setMaxGeneratedTrades(int maxGeneratedTrades) {
        this.maxGeneratedTrades = maxGeneratedTrades;
    }

    public BigDecimalRange getPriceJitter() {
        return priceJitter;
    }

    public void setPriceJitter(BigDecimalRange priceJitter) {
        this.priceJitter = priceJitter;
    }

    public static class BigDecimalRange {
        private int min = 3;
        private int max = 9;

        public BigDecimalRange() {
        }

        public BigDecimalRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public int getMin() {
            return min;
        }

        public void setMin(int min) {
            this.min = min;
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }
    }
}
