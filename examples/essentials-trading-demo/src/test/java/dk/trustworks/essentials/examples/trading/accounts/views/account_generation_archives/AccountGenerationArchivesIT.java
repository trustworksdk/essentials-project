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

package dk.trustworks.essentials.examples.trading.accounts.views.account_generation_archives;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGenerationEventStream;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Generation archival on the ledger's account, moved here from the deleted {@code brokerage} context (phase 4 Q-3): an
 * account's books are rolled, its sealed first generation's events are read, archived, listed and read back from the
 * archive.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false",
        "essentials.eventstore.archives.enabled=true"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's container is still up; a cached context outlives it
class AccountGenerationArchivesIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String   ACCOUNT = "ACC-ARCHIVE";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-archives-it-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a_sealed_generation_is_read_archived_listed_and_read_back() {
        assertThat(alice().postForEntity("/api/accounts", Map.of("accountId", ACCOUNT, "ownerId", "USR-ALICE", "currency", "USD"), Void.class)
                          .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/deposits",
                                            Map.of("accountId", ACCOUNT, "depositId", "DEP-ARCHIVE", "amount", Map.of("amount", new BigDecimal("100.00"), "currency", "USD"), "businessDate", "2030-01-07"),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var rolled = operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/generations?nextPeriodId=2099-12-31", null, String.class);
        assertThat(rolled.getStatusCode().is2xxSuccessful()).as("%s", rolled.getBody()).isTrue();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var events = operator().getForEntity("/api/admin/accounts/" + ACCOUNT + "/generations/1/events", ApiClosingBooksGenerationEventStream.class);
            assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(Objects.requireNonNull(events.getBody()).events()).isNotEmpty();
        });

        var archived = operator().postForEntity("/api/admin/accounts/" + ACCOUNT + "/generations/1/archive", null, ApiArchivedGeneration.class);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody()).isNotNull();
        assertThat(archived.getBody().aggregateType()).isEqualTo("Accounts");
        assertThat(archived.getBody().logicalAggregateId()).isEqualTo(ACCOUNT);
        assertThat(archived.getBody().generation()).isEqualTo(1);
        assertThat(archived.getBody().status()).isEqualTo("ARCHIVED");
        assertThat(archived.getBody().eventCount()).isGreaterThan(0);

        var listed = operator().getForEntity("/api/admin/accounts/" + ACCOUNT + "/archives", ApiArchivedGeneration[].class);
        assertThat(listed.getBody()).extracting(ApiArchivedGeneration::generation).containsExactly(1L);

        var content = operator().getForEntity("/api/admin/accounts/" + ACCOUNT + "/generations/1/archive-content", String.class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getBody()).contains("\"logicalAggregateId\":\"" + ACCOUNT + "\"").contains("\"aggregateType\":\"Accounts\"");
    }

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    private TestRestTemplate alice() {
        return restTemplate.withBasicAuth("alice@demo.local", "alice-demo-password");
    }
}
