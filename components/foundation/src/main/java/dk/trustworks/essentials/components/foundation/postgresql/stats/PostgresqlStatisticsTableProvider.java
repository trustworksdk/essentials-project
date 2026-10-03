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


package dk.trustworks.essentials.components.foundation.postgresql.stats;

import java.util.*;

/**
 * Contributes the tables a component owns to
 * {@link dk.trustworks.essentials.components.foundation.postgresql.api.DefaultPostgresqlTableStatisticsApi}.
 * <p>
 * Called on every statistics request rather than once, so a provider whose tables appear at runtime - the event
 * store adds an event-stream table per aggregate type it is configured with - is reported as it is now. A table
 * that does not exist (yet) is simply left out of the result.
 * <p>
 * The Spring Boot starters register one provider per component they configure, using the table names that
 * component was actually configured with.
 */
@FunctionalInterface
public interface PostgresqlStatisticsTableProvider {

    /**
     * @return the tables to report, each with the section it belongs to. May be empty
     */
    List<PostgresqlStatisticsTable> statisticsTables();

    /**
     * @param section    the section every table is reported under
     * @param tableNames the table names
     * @return a provider with a fixed set of tables
     */
    static PostgresqlStatisticsTableProvider of(String section, String... tableNames) {
        var tables = Arrays.stream(tableNames)
                           .map(tableName -> new PostgresqlStatisticsTable(section, tableName))
                           .toList();
        return () -> tables;
    }
}
