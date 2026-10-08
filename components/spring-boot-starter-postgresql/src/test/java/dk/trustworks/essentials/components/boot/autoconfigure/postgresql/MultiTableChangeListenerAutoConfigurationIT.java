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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter's {@link MultiTableChangeListener} bean, built with {@link MultiTableChangeListener#builder()} (N-14):
 * by default its LISTEN connection is borrowed from the application's pool; with
 * {@code essentials.multi-table-change-listener.dedicated-connection=true} it opens its own, outside the pool.
 */
@Testcontainers
class MultiTableChangeListenerAutoConfigurationIT {
    private static final String POOLED_APPLICATION_NAME = "essentials-pool";
    private static final String LISTEN_TABLE            = "listener_autoconfig_test";

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("starter-listener-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            EssentialsComponentsConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withPropertyValues("spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                        "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                        "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                        // Marks every pooled session, so the listener's own connection can be told apart
                                        "spring.datasource.hikari.data-source-properties.ApplicationName=" + POOLED_APPLICATION_NAME);

    @Test
    void by_default_the_listen_connection_is_borrowed_from_the_pool() {
        contextRunner.run(ctx -> {
            listenToTestTable(ctx.getBean(Jdbi.class), ctx.getBean(MultiTableChangeListener.class));

            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(countListeningSessions(ctx.getBean(Jdbi.class), true)).isEqualTo(1);
                          assertThat(countListeningSessions(ctx.getBean(Jdbi.class), false)).isZero();
                      });
        });
    }

    @Test
    void with_dedicated_connection_the_listen_connection_is_opened_outside_the_pool() {
        contextRunner.withPropertyValues("essentials.multi-table-change-listener.dedicated-connection=true")
                     .run(ctx -> {
                         assertThat(ctx.getBean(EssentialsComponentsProperties.class).getMultiTableChangeListener().isDedicatedConnection()).isTrue();
                         listenToTestTable(ctx.getBean(Jdbi.class), ctx.getBean(MultiTableChangeListener.class));

                         Awaitility.waitAtMost(Duration.ofSeconds(5))
                                   .untilAsserted(() -> {
                                       assertThat(countListeningSessions(ctx.getBean(Jdbi.class), false)).isEqualTo(1);
                                       assertThat(countListeningSessions(ctx.getBean(Jdbi.class), true)).isZero();
                                   });
                     });
    }

    @SuppressWarnings("unchecked")
    private static void listenToTestTable(Jdbi jdbi, MultiTableChangeListener<?> listener) {
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE IF NOT EXISTS " + LISTEN_TABLE + " (id bigint PRIMARY KEY)"));
        ((MultiTableChangeListener<TableChangeNotification>) listener).listenToNotificationsFor(LISTEN_TABLE, TestNotification.class);
    }

    /**
     * Sessions whose most recent statement was a LISTEN - only the listener's connection issues them
     */
    private static int countListeningSessions(Jdbi jdbi, boolean pooled) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM pg_stat_activity " +
                                                                    "WHERE datname = current_database() AND query LIKE 'LISTEN %' AND " +
                                                                    (pooled ? "application_name = :pooled" : "application_name <> :pooled"))
                                               .bind("pooled", POOLED_APPLICATION_NAME)
                                               .mapTo(Integer.class)
                                               .one());
    }

    public static class TestNotification extends TableChangeNotification {
    }
}
