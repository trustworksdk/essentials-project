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
import dk.trustworks.essentials.components.foundation.postgresql.stats.QueryStatistics;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.slf4j.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.hasAnyEssentialsSecurityRoles;

/**
 * Default implementation of the {@link PostgresqlQueryStatisticsApi} interface for retrieving PostgreSQL query statistics.
 * This class provides functionality to fetch performance data of SQL queries executed in a PostgreSQL database,
 * including support for the `pg_stat_statements` extension.
 * <p>
 * The implementation attempts to initialize the `pg_stat_statements` extension and determine
 * its availability on the target database during construction.
 */
public class DefaultPostgresqlQueryStatisticsApi implements PostgresqlQueryStatisticsApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultPostgresqlQueryStatisticsApi.class);

    private final EssentialsSecurityProvider                                    securityProvider;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private boolean                                                             pgStatementsAvailable;

    public DefaultPostgresqlQueryStatisticsApi(EssentialsSecurityProvider securityProvider,
                                               HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");

        initializePgStatStatementsAvailability();
    }

    private void initializePgStatStatementsAvailability() {
        try {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                var handle = uow.handle();
                if (PostgresqlUtil.isPGExtensionAvailable(handle, "pg_stat_statements")) {
                    // Already created - by an operator, or by an earlier start of this application.
                    this.pgStatementsAvailable = true;
                } else if (!PostgresqlUtil.isPGExtensionInstallable(handle, "pg_stat_statements")) {
                    this.pgStatementsAvailable = false;
                } else if (!PostgresqlUtil.isPGLibraryPreloaded(handle, "pg_stat_statements")) {
                    // Creatable, but its view errors on every read until the server preloads the library. Not
                    // created, so as not to leave an extension behind that cannot work.
                    log.info("pg_stat_statements is installed on the server but not in shared_preload_libraries - query statistics are unavailable");
                    this.pgStatementsAvailable = false;
                } else {
                    // Best effort. It used to be attempted only when the extension already existed, because the
                    // check above read pg_extension, so it never created anything and the statistics were silently
                    // empty. A refusal - usually a role that may not create extensions - is the operator's choice.
                    this.pgStatementsAvailable = PostgresqlUtil.executeAllowingRefusal(handle, "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;");
                    if (!pgStatementsAvailable) {
                        log.info("pg_stat_statements could not be created by this role - query statistics are unavailable until an operator creates it");
                    }
                }
                log.info("pg_stat_statements extension is {}", pgStatementsAvailable ? "available" : "not available");
            });
        } catch (Exception e) {
            this.pgStatementsAvailable = false;
            log.warn("Unable to initialize pg_stat_statements support. Query statistics API will return empty results: {}", e.getMessage());
            log.debug("Failed to initialize pg_stat_statements support", e);
        }
    }

    private void validateRoles(Object principal) {
        hasAnyEssentialsSecurityRoles(securityProvider, principal, POSTGRESQL_STATS_READER, ESSENTIALS_ADMIN);
    }

    public List<ApiQueryStatistics> getTopTenSlowestQueries(Object principal) {
        validateRoles(principal);
        return getTopTenSlowestQueries(10).stream()
                .map(ApiQueryStatistics::from)
                .toList();
    }

    private List<QueryStatistics> getTopTenSlowestQueries(int limit) {
        if(!pgStatementsAvailable) {
            return List.of();
        }
        try {
            return unitOfWorkFactory.withUnitOfWork(uow -> {
                var sql = """
                            SELECT
                              query,
                              calls,
                              total_plan_time + total_exec_time AS total_time,
                              mean_plan_time + mean_exec_time  AS mean_time
                            FROM pg_stat_statements
                            ORDER BY total_time DESC
                            LIMIT :limit;
                        """;
                return uow.handle().createQuery(sql)
                        .bind("limit", limit)
                        .map((rs, ctx) -> {
                            return new QueryStatistics(rs.getString("query"),
                                    rs.getDouble("total_time"),
                                    rs.getLong("calls"),
                                    rs.getDouble("mean_time"));
                        })
                        .list();
            });
        } catch (Exception e) {
            if (PostgresqlUtil.isPGExtensionNotLoadedException(e)) {
                log.debug("pg_stat_statements extension is not loaded, query statistics will not be available");
                return List.of();
            }
            throw e;
        }
    }

}
