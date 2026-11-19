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

import java.time.OffsetDateTime;
import java.util.*;

/**
 * The outcome of {@link Migration#verifyAfterCutover()}: per cut-over aggregate type, the old
 * table's rows above the copied maximum recorded at the cut-over. Such a row was written by an old-engine node that
 * should have been stopped; it exists only in the old table and is never merged.
 *
 * @param types per aggregate type
 */
public record LateRowsReport(List<TypeLateRows> types) {
    public LateRowsReport {
        types = List.copyOf(types);
    }

    /** @return whether no type has a late row */
    public boolean clean() {
        return types.stream().allMatch(t -> t.count() == 0);
    }

    public Optional<TypeLateRows> type(String aggregateType) {
        return types.stream().filter(t -> t.aggregateType().equals(aggregateType)).findFirst();
    }

    /**
     * @param aggregateType the aggregate type
     * @param oldTable      its old table
     * @param copiedThrough the copied maximum recorded at the cut-over
     * @param count         the old rows above it
     * @param max           the highest of them, or 0 if there is none
     * @param samples       the first 10 of them
     */
    public record TypeLateRows(String aggregateType, String oldTable, long copiedThrough, long count, long max, List<LateRow> samples) {
        public TypeLateRows {
            samples = List.copyOf(samples);
        }
    }

    public record LateRow(long globalOrder, OffsetDateTime timestamp) {
    }
}
