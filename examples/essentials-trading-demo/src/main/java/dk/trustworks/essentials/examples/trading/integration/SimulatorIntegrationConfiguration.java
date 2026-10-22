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

import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Clock;

/**
 * The beans every simulator-consuming translation slice shares. The verifier exists only when a secret file is
 * configured, i.e. under the {@code simulators} profile; a slice's webhook endpoint that needs it is conditional on it
 * too.
 */
@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorIntegrationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "trading-demo.simulators", name = "webhook-secret-file")
    public WebhookSignatureVerifier webhookSignatureVerifier(SimulatorProperties properties) {
        var secretFile = Path.of(properties.getWebhookSecretFile());
        try {
            return new WebhookSignatureVerifier(Files.readString(secretFile), properties.getSignatureTolerance(), Clock.systemUTC());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the simulators' webhook secret from '" + secretFile.toAbsolutePath() + "'", e);
        }
    }

    /**
     * The exchange-simulator's generated client ({@code src/main/openapi/exchange-simulator}), on Spring Boot's
     * {@link RestClient.Builder} so its calls are observed like the application's own. Only adapters use it; no domain
     * code sees a generated type.
     */
    @Bean
    @ConditionalOnProperty(prefix = "trading-demo.simulators.exchange", name = "base-url")
    public ApiClient exchangeSimulatorApiClient(RestClient.Builder restClientBuilder, SimulatorProperties properties) {
        var apiClient = new ApiClient(simulatorRestClient(restClientBuilder, properties));
        apiClient.setBasePath(properties.getExchange().getBaseUrl());
        return apiClient;
    }

    /**
     * The payment gateway's generated client ({@code src/main/openapi/payment-gateway}), configured like the exchange's.
     */
    @Bean
    @ConditionalOnProperty(prefix = "trading-demo.simulators.payment-gateway", name = "base-url")
    public dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.ApiClient paymentGatewayApiClient(RestClient.Builder restClientBuilder, SimulatorProperties properties) {
        var apiClient = new dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.ApiClient(simulatorRestClient(restClientBuilder, properties));
        apiClient.setBasePath(properties.getPaymentGateway().getBaseUrl());
        return apiClient;
    }

    private static RestClient simulatorRestClient(RestClient.Builder restClientBuilder, SimulatorProperties properties) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                                                                       .connectTimeout(properties.getConnectTimeout())
                                                                       .build());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return restClientBuilder.clone().requestFactory(requestFactory).build();
    }
}
