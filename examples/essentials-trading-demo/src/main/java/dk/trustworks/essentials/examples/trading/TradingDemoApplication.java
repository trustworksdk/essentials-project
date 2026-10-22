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

package dk.trustworks.essentials.examples.trading;

import dk.trustworks.essentials.examples.trading._demo_harness.QueueLoadGeneratorProperties;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Entry point for the headless trading demo application. Each bounded context registers its own configuration
 * properties; only the harness's queue load generator is registered here.
 */
@SpringBootApplication
@EnableConfigurationProperties(QueueLoadGeneratorProperties.class)
public class TradingDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingDemoApplication.class, args);
    }

    /**
     * Demo-only security: every caller is authenticated as the same principal and authorized for everything, which is
     * what makes the admin console usable without wiring an identity provider into a sample application. The admin API
     * authenticates nobody itself — it asks these two beans — so without them every request answers 401.
     * <p>
     * Never do this in a real application: it authorizes destructive admin operations for anonymous callers.
     */
    @Bean
    public EssentialsAuthenticatedUser essentialsAuthenticatedUser() {
        return new EssentialsAuthenticatedUser.AllAccessAuthenticatedUser();
    }

    @Bean
    public EssentialsSecurityProvider essentialsSecurityProvider() {
        return new EssentialsSecurityProvider.AllAccessSecurityProvider();
    }

    @Bean
    public Clock tradingDemoClock() {
        return Clock.systemDefaultZone();
    }
}
