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

import java.util.*;

/**
 * What pre-flight found. Read-only: nothing is written before a report with no refusal.
 *
 * @param tables       one report per {@link SourceTable}, in plan order
 * @param planRefusals refusals that concern the plan as a whole (the event-type id space)
 */
public record PreflightReport(List<TableReport> tables, List<Refusal> planRefusals) {
    public PreflightReport {
        tables = List.copyOf(tables);
        planRefusals = List.copyOf(planRefusals);
    }

    /** @return whether nothing refused the migration */
    public boolean passed() {
        return planRefusals.isEmpty() && tables.stream().allMatch(t -> t.refusals().isEmpty());
    }

    /** @return every refusal, plan-level first */
    public List<Refusal> refusals() {
        var all = new ArrayList<>(planRefusals);
        tables.forEach(t -> all.addAll(t.refusals()));
        return all;
    }

    /** @return the report table by aggregate type */
    public Optional<TableReport> table(String aggregateType) {
        return tables.stream().filter(t -> t.aggregateType().equals(aggregateType)).findFirst();
    }

    /** @return a readable rendering for logs and exceptions */
    public String describe() {
        var b = new StringBuilder();
        planRefusals.forEach(r -> b.append("  REFUSED (plan): ").append(r.kind()).append(": ").append(r.detail()).append('\n'));
        for (var t : tables) {
            b.append("  ").append(t.aggregateType()).append(" <- ").append(t.sourceTable()).append(": ").append(t.rows()).append(" rows, max global_order ")
             .append(t.maxGlobalOrder()).append(", sequence last_value ").append(t.sequenceLastValue()).append(", column types ").append(t.columnTypes()).append('\n');
            t.refusals().forEach(r -> b.append("    REFUSED ").append(r.kind()).append(": ").append(r.detail())
                                       .append(r.count() > 0 ? " (" + r.count() + " rows, e.g. global_order " + r.sampleGlobalOrders() + ")" : "").append('\n'));
            t.warnings().forEach(w -> b.append("    warning: ").append(w).append('\n'));
        }
        return b.toString();
    }

    /**
     * @param aggregateType     the aggregate type
     * @param sourceTable       the old table
     * @param columnTypes       the old columns' types as the catalog has them, by column name (these overrule the plan)
     * @param rows              the old table's row count
     * @param maxGlobalOrder    its {@code max(global_order)}, 0 if empty
     * @param sequenceLastValue its identity sequence's {@code last_value}, 0 if nothing was ever drawn
     * @param nonUuidCauses     {@code caused_by_event_id} values that are not canonical UUIDs: copied as null
     * @param refusals          what stops the migration
     * @param warnings          what does not stop it but must be read (a {@code uuid} aggregate id, a rerun)
     */
    public record TableReport(String aggregateType, String sourceTable, Map<String, String> columnTypes, long rows, long maxGlobalOrder, long sequenceLastValue,
                              long nonUuidCauses, List<Refusal> refusals, List<String> warnings) {
        public TableReport {
            columnTypes = Collections.unmodifiableMap(new LinkedHashMap<>(columnTypes));
            refusals = List.copyOf(refusals);
            warnings = List.copyOf(warnings);
        }

        public boolean refused(RefusalKind kind) {
            return refusals.stream().anyMatch(r -> r.kind() == kind);
        }
    }

    /**
     * @param kind               what was refused
     * @param detail             a sentence for the operator
     * @param count              the offending rows, or 0 for a refusal that is not about rows
     * @param sampleGlobalOrders up to 10 offending rows' {@code global_order}s
     */
    public record Refusal(RefusalKind kind, String detail, long count, List<Long> sampleGlobalOrders) {
        public Refusal {
            sampleGlobalOrders = List.copyOf(sampleGlobalOrders);
        }

        static Refusal of(RefusalKind kind, String detail) {
            return new Refusal(kind, detail, 0, List.of());
        }
    }

    public enum RefusalKind {
        /** The old table does not exist */
        SOURCE_TABLE_MISSING,
        /** A configured column is absent from the old table */
        COLUMN_MISSING,
        /** An id column is neither {@code text}/{@code varchar} nor {@code uuid}, or a JSON column neither {@code json} nor {@code jsonb} */
        UNSUPPORTED_COLUMN_TYPE,
        /** {@code global_order} has no identity sequence */
        NO_IDENTITY_SEQUENCE,
        /** The identity sequence's cache is not 1, which the epoch rule needs */
        SEQUENCE_CACHE_NOT_ONE,
        /** An {@code event_id} is not a lower-case canonical UUID */
        EVENT_ID_NOT_CANONICAL_UUID,
        /** A {@code correlation_id} is not a lower-case canonical UUID */
        CORRELATION_ID_NOT_CANONICAL_UUID,
        /** An {@code event_revision} does not parse or does not fit in {@code smallint} */
        EVENT_REVISION_NOT_SMALLINT,
        /** A value the event log requires (aggregate id, event order, type, timestamp, payload, metadata) is null */
        REQUIRED_VALUE_NULL,
        /** The aggregate type does not fit the event log's derived table names */
        AGGREGATE_TYPE_NAME,
        /** More distinct event types than the event log's {@code smallint} ids hold */
        TOO_MANY_EVENT_TYPES,
        /** The target event table holds rows that this migration did not copy */
        TARGET_NOT_EMPTY,
        /** The aggregate type was already cut over */
        ALREADY_CUT_OVER,
        /** An earlier run of this migration copied the type from another old table */
        OTHER_SOURCE_TABLE
    }
}
