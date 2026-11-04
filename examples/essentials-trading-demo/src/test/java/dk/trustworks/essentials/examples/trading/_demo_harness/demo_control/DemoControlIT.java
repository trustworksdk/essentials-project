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

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The operator page's API (phase 8 §5) against the real exchange, with its trading sessions, frozen on Monday
 * 2030-01-07 08:00 New York: jumps fast-forward through each close, the clock's controls, and background traffic that
 * trades once the market opens.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt",
        "trading-demo.market-data.stale-after=2s"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoControlIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-control-it-db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeWithTradingSessions()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", () -> "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @Order(1)
    void the_clock_starts_before_the_open_and_skips_to_five_minutes_before_it() {
        var clock = clock();
        assertThat(clock.get("phase")).isEqualTo("PRE_OPEN");
        assertThat((String) clock.get("phaseText")).startsWith("Before the open - 08:00 New York, Monday 2030-01-07");
        assertThat(clock.get("running")).isEqualTo(false);

        assertThat(operator().postForEntity("/api/admin/demo/clock/skip", Map.of("to", "NEXT_OPEN"), String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitJobDone();

        assertThat(clock().get("newYorkTime")).asString().startsWith("2030-01-07T09:25");
        assertThat(clock().get("running")).as("a jump leaves the clock as it found it").isEqualTo(false);
    }

    @Test
    @Order(2)
    void advancing_a_business_day_processes_its_close_and_stops_before_the_next_open() {
        assertThat(operator().postForEntity("/api/admin/demo/clock/advance", Map.of("businessDays", 1), String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitJobDone();

        var clock = clock();
        assertThat(clock.get("newYorkTime")).asString().startsWith("2030-01-08T09:25");
        assertThat(clock.get("lastClosedBusinessDate")).isEqualTo("2030-01-07");
        assertThat(clock.get("openBusinessDate")).isEqualTo("2030-01-08");
        assertThat(((Map<?, ?>) clock.get("job")).get("closesDone")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void a_second_jump_is_refused_while_one_runs_and_a_bad_speed_is_refused() {
        assertThat(operator().exchange("/api/admin/demo/clock/speed", HttpMethod.PUT, new HttpEntity<>(Map.of("speed", 7)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(operator().postForEntity("/api/admin/demo/clock/advance", Map.of("businessDays", 0), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(4)
    void background_traffic_trades_once_the_market_opens() {
        assertThat(operator().postForEntity("/api/admin/demo/traffic/start", Map.of("ordersPerExchangeMinute", 120), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(traffic().get("customers")).isEqualTo(20));

        assertThat(operator().exchange("/api/admin/demo/clock/speed", HttpMethod.PUT, new HttpEntity<>(Map.of("speed", 60)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operator().postForEntity("/api/admin/demo/clock/resume", null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clock().get("running")).isEqualTo(true);

        // 09:25 at 60x: the open is 5 real seconds away
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(String.valueOf(traffic().get("outcomesLastMinute"))).contains("order FILLED"));
        assertThat(clock().get("phase")).isEqualTo("OPEN");
        // 120 per exchange minute at 60x asks for 120 a real second: the cap (10 by default) holds it down
        assertThat(traffic()).containsEntry("capped", true).containsEntry("maxOrdersPerRealSecond", 10);

        assertThat(operator().postForEntity("/api/admin/demo/traffic/stop", null, Map.class).getBody()).containsEntry("running", false);
        assertThat(operator().postForEntity("/api/admin/demo/clock/pause", null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clock().get("running")).isEqualTo(false);
        assertThat(operator().getForEntity("/api/admin/demo/overview", Map.class).getBody()).containsKeys("ordersByStatus", "fencedLocksByInstance");
    }

    @Test
    @Order(5)
    void a_customer_cannot_use_it() {
        assertThat(restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password").getForEntity("/api/admin/demo/clock", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void awaitJobDone() {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(((Map<?, ?>) clock().get("job")).get("state")).isEqualTo("DONE"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> clock() {
        return operator().getForEntity("/api/admin/demo/clock", Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> traffic() {
        return operator().getForEntity("/api/admin/demo/traffic", Map.class).getBody();
    }

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }
}
