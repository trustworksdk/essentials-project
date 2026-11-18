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

package dk.trustworks.essentials.examples.trading.accounts;

import dk.trustworks.essentials.examples.trading.accounts.views.account_activity.ActivityEntry;
import dk.trustworks.essentials.examples.trading.integration.ExchangeClosingBooksCalendar;
import dk.trustworks.essentials.examples.trading.integration.SimulatorImages;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.TestControlApi;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.ClockAdvance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The accounts roll their books at the exchange's close, not at midnight (EF-1, D32): an account's period is the
 * exchange's business date, and the first change to it after the exchange closed that day opens the next generation in
 * the next business date.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false",
        "trading-demo.simulators.webhook-secret-file=simulators/secrets/webhook-secret.txt"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's containers are still up; a cached context outlives them
class AccountBooksAtTheExchangeCloseIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT = "ACC-ROLL";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-books-at-close-it-db")
            .withUsername("test")
            .withPassword("test");

    /**
     * Frozen on Monday 2030-01-07 08:00 New York, so the test moves the business days on.
     */
    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt()
                                                              .withEnv("EXCHANGE__CLOCK__RUNNING", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("trading-demo.simulators.exchange.base-url", AccountBooksAtTheExchangeCloseIT::exchangeUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ExchangeClosingBooksCalendar calendar;

    @Test
    void the_books_roll_at_the_exchanges_close_into_the_next_business_date() {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        deposit("DEP-MON-1");
        deposit("DEP-MON-2");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(activity()).extracting(ActivityEntry::kind).containsSubsequence("DEPOSIT", "DEPOSIT", "OPENED"));
        assertThat(activity()).as("no close yet, one generation").extracting(ActivityEntry::generation).containsOnly(1L);

        // Monday 08:00 + 12 hours: past Monday's 16:30 close, Tuesday is open
        testControl().advanceClock(new ClockAdvance().days(0).hours(12).minutes(0).seconds(0));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(calendar.currentBusinessDate()).contains(LocalDate.parse("2030-01-08")));

        deposit("DEP-TUE-1");
        deposit("DEP-TUE-2");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var activity = activity();
            assertThat(activity).extracting(ActivityEntry::kind).contains("BOOKS_CLOSED");
            assertThat(activity).as("one roll, not one per change").filteredOn(entry -> entry.kind().equals("BOOKS_CLOSED")).hasSize(1);
            assertThat(activity).filteredOn(entry -> entry.reference() != null && entry.reference().startsWith("DEP-TUE"))
                                .extracting(ActivityEntry::generation).containsOnly(2L);
            assertThat(activity).filteredOn(entry -> entry.reference() != null && entry.reference().startsWith("DEP-MON"))
                                .extracting(ActivityEntry::generation).containsOnly(1L);
        });
    }

    private void deposit(String depositId) {
        assertThat(operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/deposits",
                                            Map.of("accountId", ACCOUNT, "depositId", depositId, "amount", Map.of("amount", new BigDecimal("100.00"), "currency", "USD"),
                                                   "businessDate", "2030-01-07"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<ActivityEntry> activity() {
        return alice().exchange("/api/accounts/" + ACCOUNT + "/activity", HttpMethod.GET, null, new ParameterizedTypeReference<List<ActivityEntry>>() {}).getBody();
    }

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }

    private static TestControlApi testControl() {
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(exchangeUrl());
        return new TestControlApi(apiClient);
    }

    private static String exchangeUrl() {
        return "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080);
    }
}
