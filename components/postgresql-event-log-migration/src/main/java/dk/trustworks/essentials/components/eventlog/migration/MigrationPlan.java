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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;

import javax.sql.DataSource;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * What the migration is told. The old and the new tables live in one database, so one {@link DataSource} serves
 * both.
 *
 * @param dataSource   the application's database, holding the old engine's tables and the event log's
 * @param eventLog     a started {@link EventLog} on that database, built with the schema ownership the application
 *                     uses; the migration registers each aggregate type through it ({@code prepare}) and interns event
 *                     types through its registry. It must run no subscriptions
 * @param tables       the aggregate types to move; aggregate types, old tables and derived event log tables distinct
 * @param settings     how the copy runs
 * @param cutoverSteps run in order under the cut-over's locks ({@link CutoverStep}), after its built-in verification
 */
public record MigrationPlan(DataSource dataSource, EventLog eventLog, List<SourceTable> tables, CopySettings settings, List<CutoverStep> cutoverSteps) {
    public MigrationPlan {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(eventLog, "No eventLog provided");
        requireNonEmpty(requireNonNull(tables, "No tables provided"), "No tables provided");
        requireNonNull(settings, "No settings provided");
        tables = List.copyOf(tables);
        cutoverSteps = List.copyOf(requireNonNull(cutoverSteps, "No cutoverSteps provided"));
        var types = new HashSet<String>();
        var sources = new HashSet<String>();
        for (var table : tables) {
            requireTrue(types.add(table.aggregateType().toLowerCase(Locale.ROOT)), "Aggregate type '" + table.aggregateType() +
                    "' appears twice in the plan (aggregate types that differ only in case share the event log's tables)");
            requireTrue(sources.add(table.tableName().toLowerCase(Locale.ROOT)), "Old table '" + table.tableName() + "' appears twice in the plan");
        }
    }

    public MigrationPlan(DataSource dataSource, EventLog eventLog, List<SourceTable> tables, CopySettings settings) {
        this(dataSource, eventLog, tables, settings, List.of());
    }
}
