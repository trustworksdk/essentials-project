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
package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long {@link ExchangeOrderRouter} keeps trying to reach an unavailable exchange before it rejects the order
 * {@code EXCHANGE_UNAVAILABLE} (design §4). Registered by {@code OrdersConfiguration}.
 * <p>
 * The retries run inside one delivery of {@code OrderPlaced}, so all attempts and the waits between them must finish well
 * inside {@code essentials.durable-queues.message-handling-timeout} (30s by default). The defaults wait 0.25 + 0.5 + 1s.
 */
@ConfigurationProperties(prefix = "trading-demo.orders.exchange-submission")
public class ExchangeSubmissionProperties {
    /**
     * Submissions tried in total, the first included, before the order is rejected.
     */
    private int      attempts       = 4;
    /**
     * The wait before the first retry; it doubles before each further one.
     */
    private Duration initialBackoff = Duration.ofMillis(250);

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }
}
