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

package dk.trustworks.essentials.components.foundation.postgresql.api;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The best-effort {@code CREATE EXTENSION pg_stat_statements}, against real servers.
 * <p>
 * It used to be attempted only when the extension already existed: the check read {@code pg_extension} - installed -
 * where it meant {@code pg_available_extensions} - installable. So it never created anything, and the query
 * statistics were silently empty on every database whose operator had not created the extension by hand, including
 * the trading demo's, whose server preloads the library.
 */
@Testcontainers
class DefaultPostgresqlQueryStatisticsApiIT {

    @Container
    static final PostgreSQLContainer preloaded = new PostgreSQLContainer("postgres:17.5")
            .withDatabaseName("test-db")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Container
    static final PostgreSQLContainer notPreloaded = new PostgreSQLContainer("postgres:17.5")
            .withDatabaseName("test-db");

    private static final EssentialsSecurityProvider ALL_ACCESS = new EssentialsSecurityProvider.AllAccessSecurityProvider();

    @Test
    void the_extension_is_created_where_the_server_preloads_it() {
        var jdbi = jdbi(preloaded, preloaded.getUsername(), preloaded.getPassword());
        dropExtension(jdbi);
        assertThat(installed(jdbi)).isFalse();

        new DefaultPostgresqlQueryStatisticsApi(ALL_ACCESS, new JdbiUnitOfWorkFactory(jdbi));

        assertThat(installed(jdbi)).as("created at startup, not only reported").isTrue();
    }

    @Test
    void the_extension_is_not_created_where_the_server_does_not_preload_it() {
        // Creatable there, but its view errors on every read until the server preloads the library - so leaving it
        // installed would only leave something broken behind.
        var jdbi = jdbi(notPreloaded, notPreloaded.getUsername(), notPreloaded.getPassword());
        var api  = new DefaultPostgresqlQueryStatisticsApi(ALL_ACCESS, new JdbiUnitOfWorkFactory(jdbi));

        assertThat(installed(jdbi)).isFalse();
        assertThat(api.getTopTenSlowestQueries("principal")).isEmpty();
    }

    @Test
    void a_role_that_may_not_create_extensions_is_refused_without_failing() {
        var admin = jdbi(preloaded, preloaded.getUsername(), preloaded.getPassword());
        dropExtension(admin);
        admin.useHandle(handle -> {
            handle.execute("DROP ROLE IF EXISTS stats_app");
            handle.execute("CREATE ROLE stats_app LOGIN PASSWORD 'secret'");
            handle.execute("GRANT CONNECT ON DATABASE \"test-db\" TO stats_app");
        });
        var app = jdbi(preloaded, "stats_app", "secret");

        var api = new DefaultPostgresqlQueryStatisticsApi(ALL_ACCESS, new JdbiUnitOfWorkFactory(app));

        assertThat(installed(admin)).isFalse();
        assertThat(api.getTopTenSlowestQueries("principal")).isEmpty();
    }

    @Test
    void a_refused_statement_leaves_the_unit_of_work_usable() {
        // Without the savepoint, a refusal aborts the transaction and everything after it in the same unit of work
        // fails with "current transaction is aborted" - which is how a refused CREATE EXTENSION pg_cron would have
        // taken the scheduler's start down with it.
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi(notPreloaded, notPreloaded.getUsername(), notPreloaded.getPassword()));

        var afterRefusal = unitOfWorkFactory.withUnitOfWork(uow -> {
            assertThat(PostgresqlUtil.executeAllowingRefusal(uow.handle(), "CREATE EXTENSION no_such_extension")).isFalse();
            return uow.handle().createQuery("SELECT 1").mapTo(Integer.class).one();
        });

        assertThat(afterRefusal).isEqualTo(1);
    }

    @Test
    void installable_preloaded_and_installed_are_three_different_questions() {
        var jdbi = jdbi(notPreloaded, notPreloaded.getUsername(), notPreloaded.getPassword());
        jdbi.useHandle(handle -> {
            assertThat(PostgresqlUtil.isPGExtensionInstallable(handle, "pg_stat_statements")).isTrue();
            assertThat(PostgresqlUtil.isPGLibraryPreloaded(handle, "pg_stat_statements")).isFalse();
            assertThat(PostgresqlUtil.isPGExtensionAvailable(handle, "pg_stat_statements")).isFalse();
            assertThat(PostgresqlUtil.isPGExtensionInstallable(handle, "no_such_extension")).isFalse();
        });
        jdbi(preloaded, preloaded.getUsername(), preloaded.getPassword())
                .useHandle(handle -> assertThat(PostgresqlUtil.isPGLibraryPreloaded(handle, "pg_stat_statements")).isTrue());
    }

    private static Jdbi jdbi(PostgreSQLContainer container, String user, String password) {
        return Jdbi.create(container.getJdbcUrl(), user, password);
    }

    private static boolean installed(Jdbi jdbi) {
        return jdbi.withHandle(handle -> PostgresqlUtil.isPGExtensionAvailable(handle, "pg_stat_statements"));
    }

    private static void dropExtension(Jdbi jdbi) {
        jdbi.useHandle(handle -> handle.execute("DROP EXTENSION IF EXISTS pg_stat_statements"));
    }
}
