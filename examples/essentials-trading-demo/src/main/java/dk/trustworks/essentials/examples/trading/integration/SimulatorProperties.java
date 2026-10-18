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

package dk.trustworks.essentials.examples.trading.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the simulators are and how their webhooks are signed. Set by the {@code simulators} Spring profile
 * ({@code application-simulators.yml}); absent otherwise, and then nothing that talks to a simulator is created.
 */
@ConfigurationProperties(prefix = "trading-demo.simulators")
public class SimulatorProperties {
    private final Endpoint exchange       = new Endpoint();
    private final Endpoint paymentGateway = new Endpoint();
    /**
     * A file holding the shared webhook signing secret, trimmed when read. Both simulators sign with it.
     */
    private String         webhookSecretFile;
    /**
     * How far a delivery's signature timestamp may be from this JVM's clock. The simulators' guide suggests 5 minutes.
     */
    private Duration       signatureTolerance = Duration.ofMinutes(5);

    public Endpoint getExchange() {
        return exchange;
    }

    public Endpoint getPaymentGateway() {
        return paymentGateway;
    }

    public String getWebhookSecretFile() {
        return webhookSecretFile;
    }

    public void setWebhookSecretFile(String webhookSecretFile) {
        this.webhookSecretFile = webhookSecretFile;
    }

    public Duration getSignatureTolerance() {
        return signatureTolerance;
    }

    public void setSignatureTolerance(Duration signatureTolerance) {
        this.signatureTolerance = signatureTolerance;
    }

    public static class Endpoint {
        /**
         * The simulator's base URL, e.g. {@code http://localhost:18080}.
         */
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
