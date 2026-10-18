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

package dk.trustworks.essentials.examples.trading.identity;

import dk.trustworks.essentials.examples.trading.identity.views.users.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Identity end to end over HTTP: an operator registers a customer, the customer logs in and reaches what a customer
 * may and nothing else, a duplicate email is refused, a lock applies to the very next request, and no password ever
 * reaches the event store.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        "trading-demo.queue-load.enabled=false"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class IdentityIT {
    private static final String PASSWORD = "carol-secret-password-1";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-identity-it-db")
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
    @Autowired
    private JdbcTemplate     jdbcTemplate;

    private TestRestTemplate operator() {
        return restTemplate.withBasicAuth("operator@demo.local", "operator-demo-password");
    }

    @Test
    void a_registered_customer_logs_in_reaches_only_customer_paths_and_is_locked_out_at_once() {
        var registration = Map.of("userId", "USR-CAROL",
                                  "email", "Carol@Demo.Local ",
                                  "displayName", "Carol Customer",
                                  "roles", List.of("CUSTOMER"),
                                  "password", PASSWORD);

        assertThat(operator().postForEntity("/api/admin/users", registration, Void.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The same address in another spelling is the same email
        assertThat(operator().postForEntity("/api/admin/users",
                                            Map.of("userId", "USR-CAROL-2", "email", "carol@demo.local", "displayName", "Carol Again",
                                                   "roles", List.of("CUSTOMER"), "password", PASSWORD),
                                            String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var carol = restTemplate.withBasicAuth("carol@demo.local", PASSWORD);
        // Logged in, but a customer may not reach operator paths
        assertThat(carol.getForEntity("/api/admin/users", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.withBasicAuth("carol@demo.local", "wrong-password").getForEntity("/api/admin/users", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Not behind a login at all
        assertThat(restTemplate.getForEntity("/api/admin/users", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(operator().getForEntity("/api/admin/users/USR-CAROL", UserSummary.class).getBody())
                .isNotNull()
                .satisfies(user -> {
                    assertThat(user.email().toString()).isEqualTo("carol@demo.local");
                    assertThat(user.locked()).isFalse();
                }));

        assertThat(operator().postForEntity("/api/admin/users/USR-CAROL/lock?reason=testing", null, Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The very next request, no projection in between
        assertThat(carol.getForEntity("/api/admin/users", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(operator().postForEntity("/api/admin/users/USR-CAROL/unlock", null, Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(carol.getForEntity("/api/admin/users", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void no_password_reaches_the_event_store() {
        operator().postForEntity("/api/admin/users",
                                 Map.of("userId", "USR-DAVE", "email", "dave@demo.local", "displayName", "Dave",
                                        "roles", List.of("CUSTOMER"), "password", "dave-very-secret-password"),
                                 Void.class);

        var eventTables = jdbcTemplate.queryForList("""
                                                            SELECT table_name FROM information_schema.tables
                                                            WHERE table_schema = 'public' AND table_name LIKE '%users%' AND table_name LIKE '%event%'
                                                            """, String.class);
        assertThat(eventTables).as("the Users event table").isNotEmpty();
        for (var table : eventTables) {
            var rows = jdbcTemplate.queryForList("SELECT CAST(t AS text) FROM " + table + " t", String.class);
            assertThat(rows).noneMatch(row -> row.contains("dave-very-secret-password") || row.contains("{bcrypt}"));
        }
    }
}
