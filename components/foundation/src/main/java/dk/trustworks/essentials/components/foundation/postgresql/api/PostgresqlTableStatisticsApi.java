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

import java.util.List;

/**
 * Size, activity and cache statistics for the PostgreSQL tables the Essentials components own - event streams,
 * durable subscriptions and gap tables, the CDC inbox, durable and shard-owned queues, fenced locks, aggregate
 * snapshots/closing-books/archives, and Essentials' own bookkeeping tables.
 * <p>
 * Which tables are reported is decided by the registered
 * {@link dk.trustworks.essentials.components.foundation.postgresql.stats.PostgresqlStatisticsTableProvider}s; each
 * table carries the section it belongs to, so a client can group them.
 */
public interface PostgresqlTableStatisticsApi {

    /**
     * @param principal the principal requesting the statistics
     * @return statistics for every reported table that exists, grouped by section. Empty if no table is reported
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not
     *                                                                               authorized
     */
    List<ApiTableStatistics> fetchTableStatistics(Object principal);
}
