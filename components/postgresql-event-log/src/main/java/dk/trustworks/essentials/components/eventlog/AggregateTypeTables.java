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
package dk.trustworks.essentials.components.eventlog;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.Locale;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The validated database names that belong to one aggregate type: its event table, its stream head table, the named
 * {@code UNIQUE(aggregate_id, event_order)} constraint and the {@code event_id} index.
 * <p>
 * Every name is derived from the aggregate type ({@code el_<aggregate type, lower-cased>_events} and
 * {@code el_<...>_heads}) and validated with {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} before it is
 * ever concatenated into SQL. A name that would exceed {@link PostgresqlUtil#MAX_IDENTIFIER_LENGTH} bytes is refused,
 * never truncated: PostgreSQL would silently cut it, and the engine maps unique violations by constraint name. The
 * longest derived name is the constraint, so an aggregate type may have at most {@link #MAX_AGGREGATE_TYPE_LENGTH}
 * characters.
 *
 * @param aggregateType         the aggregate type as the application names it
 * @param eventTable            {@code el_<type>_events}
 * @param headTable             {@code el_<type>_heads}
 * @param streamOrderConstraint {@code el_<type>_events_stream_order}
 * @param eventIdIndex          {@code el_<type>_events_event_id}
 */
public record AggregateTypeTables(String aggregateType, String eventTable, String headTable, String streamOrderConstraint, String eventIdIndex) {
    static final String PREFIX                  = "el_";
    static final String EVENTS_SUFFIX           = "_events";
    static final String HEADS_SUFFIX            = "_heads";
    static final String STREAM_ORDER_SUFFIX     = "_stream_order";
    static final String EVENT_ID_INDEX_SUFFIX   = "_event_id";
    static final String EVENT_TYPE_INDEX_SUFFIX = "_type_order";
    static final String TENANT_INDEX_SUFFIX     = "_tenant_order";
    static final String CAUSED_BY_INDEX_SUFFIX  = "_caused_by";

    /**
     * The longest aggregate type whose derived names all fit in {@link PostgresqlUtil#MAX_IDENTIFIER_LENGTH}
     */
    public static final int MAX_AGGREGATE_TYPE_LENGTH = PostgresqlUtil.MAX_IDENTIFIER_LENGTH - PREFIX.length() - EVENTS_SUFFIX.length() - STREAM_ORDER_SUFFIX.length();

    public AggregateTypeTables {
        requireNonBlank(aggregateType, "No aggregateType provided");
        for (var name : new String[]{eventTable, headTable, streamOrderConstraint, eventIdIndex}) {
            requireNonBlank(name, "Missing a derived name for aggregate type " + aggregateType);
            requireTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PostgresqlUtil.MAX_IDENTIFIER_LENGTH,
                        "Aggregate type '" + aggregateType + "' is too long: its derived name '" + name + "' exceeds " + PostgresqlUtil.MAX_IDENTIFIER_LENGTH +
                                " bytes, and PostgreSQL would truncate it. Use an aggregate type of at most " + MAX_AGGREGATE_TYPE_LENGTH + " characters");
            PostgresqlUtil.checkIsValidTableOrColumnName(name, "aggregate type '" + aggregateType + "'");
        }
    }

    /**
     * @param aggregateType the aggregate type; letters, digits and underscores, at most {@link #MAX_AGGREGATE_TYPE_LENGTH}
     *                      characters. Lower-cased into the table names, so two types that differ only in case map to
     *                      the same tables, which the engine refuses at registration
     * @return the validated names
     * @throws IllegalArgumentException (or {@code InvalidTableOrColumnNameException}) if a derived name is not a valid,
     *                                  untruncated identifier
     */
    /**
     * @return {@code el_<type>_events_type_order}, the opt-in {@code (event_type, global_order)} index; shorter than
     * {@link #streamOrderConstraint()}, so it fits whenever the other names do
     */
    public String eventTypeIndex() {
        var name = eventTable + EVENT_TYPE_INDEX_SUFFIX;
        PostgresqlUtil.checkIsValidTableOrColumnName(name, "aggregate type '" + aggregateType + "'");
        return name;
    }

    /**
     * @return {@code el_<type>_events_tenant_order}, the opt-in {@code (tenant, global_order)} index; as long as
     * {@link #streamOrderConstraint()}, so it fits whenever the other names do
     */
    public String tenantIndex() {
        var name = eventTable + TENANT_INDEX_SUFFIX;
        PostgresqlUtil.checkIsValidTableOrColumnName(name, "aggregate type '" + aggregateType + "'");
        return name;
    }

    /**
     * @return {@code el_<type>_events_caused_by}, the partial {@code caused_by_event_id} index behind the causation
     * lookup; shorter than {@link #streamOrderConstraint()}, so it fits whenever the other
     * names do
     */
    public String causedByIndex() {
        var name = eventTable + CAUSED_BY_INDEX_SUFFIX;
        PostgresqlUtil.checkIsValidTableOrColumnName(name, "aggregate type '" + aggregateType + "'");
        return name;
    }

    public static AggregateTypeTables of(String aggregateType) {
        requireNonBlank(aggregateType, "No aggregateType provided");
        var events = PREFIX + aggregateType.toLowerCase(Locale.ROOT) + EVENTS_SUFFIX;
        var heads  = PREFIX + aggregateType.toLowerCase(Locale.ROOT) + HEADS_SUFFIX;
        return new AggregateTypeTables(aggregateType, events, heads, events + STREAM_ORDER_SUFFIX, events + EVENT_ID_INDEX_SUFFIX);
    }
}
