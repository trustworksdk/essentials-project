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

package dk.trustworks.essentials.examples.trading.funding.external_systems.payment_gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long {@link PaymentRouter} keeps trying to reach an unavailable gateway before it records the payment failed
 * {@code GATEWAY_UNAVAILABLE}. All attempts run within one delivery and must finish well inside
 * {@code essentials.durable-queues.message-handling-timeout} (30s). Registered by {@code FundingConfiguration}.
 */
@ConfigurationProperties(prefix = "trading-demo.funding.payment-submission")
public class PaymentSubmissionProperties {
    private int      attempts       = 4;
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
