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
    private final Exchange exchange       = new Exchange();
    private final Endpoint paymentGateway = new Endpoint();
    /**
     * A file holding the shared webhook signing secret, trimmed when read. Both simulators sign with it.
     */
    private String         webhookSecretFile;
    /**
     * How far a delivery's signature timestamp may be from this JVM's clock. The simulators' guide suggests 5 minutes.
     */
    private Duration       signatureTolerance = Duration.ofMinutes(5);
    /**
     * How long a call to a simulator may take to connect.
     */
    private Duration       connectTimeout     = Duration.ofSeconds(2);
    /**
     * How long a call to a simulator may wait for its answer. Kept short: a submission is retried within one delivery of
     * {@code OrderPlaced} ({@code trading-demo.orders.exchange-submission.*}), and every attempt must fit inside the
     * message-handling timeout.
     */
    private Duration       readTimeout        = Duration.ofSeconds(5);

    public Exchange getExchange() {
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

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
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

    /**
     * The exchange-simulator, and who the brokerage is there.
     */
    public static class Exchange extends Endpoint {
        /**
         * The firm's participant id at the exchange: every client order is the firm's (D8, omnibus). Registered by the
         * exchange adapter the first time the exchange does not know it.
         */
        private String participantId   = "DEMO-BROKER";
        private String participantName = "Essentials Trading Demo Brokerage";

        public String getParticipantId() {
            return participantId;
        }

        public void setParticipantId(String participantId) {
            this.participantId = participantId;
        }

        public String getParticipantName() {
            return participantName;
        }

        public void setParticipantName(String participantName) {
            this.participantName = participantName;
        }
    }
}
