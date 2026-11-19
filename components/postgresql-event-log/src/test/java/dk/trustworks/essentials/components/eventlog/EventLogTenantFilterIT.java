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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The tenant filter in SQL. Catch-up and bridge reads carry {@code tenant = ?} (with the type
 * predicate when the subscription filters on event types too), live batches are filtered in memory. A rare tenant's
 * catch-up reads none of the other tenants' rows; a held event of the tenant below the reader's cursor is delivered
 * once whether the catch-up passes it or a restarted reader's bridge does (the bridge under an unknown watermark must
 * count non-matching runs as gaps, never as handled); the opt-in {@code (tenant, global_order)} index is built only
 * when the engine creates the schema.
 */
class EventLogTenantFilterIT extends AbstractSubscriptionIT {
    private static final String           MINE     = "tenant-a";
    private static final String           OTHER    = "tenant-b";
    private static final EventLogSettings FILTERED = FAST.withCatchUpPageSize(100).withCatchUpScanWindow(1_000, 1_000_000);

    // ---- catch-up: a rare tenant reads only its own rows ----

    @ParameterizedTest(name = "indexed={0}")
    @ValueSource(booleans = {false, true})
    void a_rare_tenants_catch_up_reads_none_of_the_other_tenants_rows(boolean indexed) throws Exception {
        restartEventLog(dataSource, indexed ? FILTERED.withTenantIndexed(Set.of(TYPE)) : FILTERED);
        if (indexed) {
            assertThat(eventLog.tenantIndex().build(TYPE).get(60, TimeUnit.SECONDS)).as("the tenant index is built and valid").isTrue();
        }
        var mine = new TreeSet<Long>();
        for (var round = 0; round < 5; round++) {
            append("common-" + round % 7, "E", OTHER, 400);
            mine.add(append("rare-" + round, "E", MINE, 1));
        }
        append("common-tail", "E", OTHER, 400);
        var recorder = new Recorder();
        var tested   = subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key("tested", TYPE, "node-1"), 1, ConsistencyMode.FRESH, recorder, TestSeams.NONE)
                                                              .withTenant(MINE));
        awaitDelivered(recorder, mine.stream().mapToLong(Long::longValue).toArray());
        awaitTrue(tested::isIdle);
        assertThat(recorder.deliveredOrders()).isEqualTo(mine);
        assertThat(recorder.delivered).allSatisfy(event -> assertThat(event.tenant()).isEqualTo(MINE));
        // 2 405 rows below the head, 5 of them the tenant's: every catch-up and bridge statement returned only those
        assertThat(reads.catchUpRows.get("tested").get()).as("rows read by the catch-up and bridge statements").isEqualTo(mine.size());
        if (indexed) {
            assertThat(reads.indexedFilteredReads.get("tested")).as("filtered reads served by the tenant index").isNotNull();
        }
    }

    // ---- catch-up and bridge: a held event of the tenant below the reader's cursor ----

    @ParameterizedTest
    @CsvSource({"FRESH, false", "CONTIGUOUS, false", "FRESH, true", "CONTIGUOUS, true"})
    void a_held_event_of_the_tenant_below_the_cursor_is_delivered_once_by_the_catch_up(ConsistencyMode mode, boolean withType) throws Exception {
        var outcome = heldEvent(mode, withType, false, Set.of());
        assertDeliveredOnce(outcome);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_held_event_of_the_tenant_behind_a_restarted_reader_is_delivered_once_by_the_bridge(boolean withType) throws Exception {
        var outcome = heldEvent(ConsistencyMode.FRESH, withType, true, Set.of());
        assertDeliveredOnce(outcome);
        // The tenant-filtered bridge under the unknown watermark left the non-matching runs as gaps and handed them off
        assertThat(reads.handedOffBy).contains("tested");
    }

    @Test
    void must_fail_a_tenant_filtered_bridge_marking_its_range_handled_under_the_unknown_watermark_loses_the_held_event() throws Exception {
        var outcome = heldEvent(ConsistencyMode.FRESH, false, true, Set.of(WatermarkMutant.FILTERED_BRIDGE_MARKS_RANGE_UNDER_UNKNOWN_WATERMARK));
        assertThat(outcome.missed()).as("the held event, marked handled by the bridge").contains(outcome.held());
    }

    // ---- the tenant filter combined with the type filter, catch-up and live ----

    @Test
    void the_tenant_filter_combines_with_the_type_filter_on_catch_up_and_live_batches() throws Exception {
        restartEventLog(dataSource, FILTERED);
        var expected = new TreeSet<Long>();
        for (var round = 0; round < 10; round++) {
            expected.add(append("x-a-" + round % 3, "X", MINE, 1));
            append("y-a-" + round % 3, "Y", MINE, 20);
            append("x-b-" + round % 3, "X", OTHER, 20);
            append("y-b-" + round % 3, "Y", OTHER, 20);
        }
        var recorder = new Recorder();
        var tested   = subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key("tested", TYPE, "node-1"), 1, ConsistencyMode.FRESH, recorder, TestSeams.NONE)
                                                              .withFilter(EventTypeFilter.of("X"))
                                                              .withTenant(MINE));
        awaitDelivered(recorder, expected.stream().mapToLong(Long::longValue).toArray());
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        var caughtUpRows = reads.catchUpRows.get("tested").get();
        assertThat(caughtUpRows).as("rows read by the catch-up and bridge statements").isEqualTo(expected.size());
        // Live: the reader's batches carry every tenant and type; only (X, tenant-a) reaches the handler
        for (var round = 0; round < 5; round++) {
            append("y-b-live", "Y", OTHER, 3);
            append("x-b-live", "X", OTHER, 3);
            append("y-a-live", "Y", MINE, 3);
            expected.add(append("x-a-live", "X", MINE, 1));
        }
        awaitDelivered(recorder, expected.stream().mapToLong(Long::longValue).toArray());
        awaitTrue(tested::isIdle);
        assertThat(recorder.deliveredOrders()).isEqualTo(expected);
        assertThat(recorder.delivered).allSatisfy(event -> {
            assertThat(event.tenant()).isEqualTo(MINE);
            assertThat(event.eventType()).isEqualTo("X");
        });
        assertThat(recorder.delivered).as("each once").hasSize(expected.size());
    }

    // ---- the opt-in (tenant, global_order) index ----

    @Test
    void the_tenant_index_is_built_concurrently_when_the_engine_creates_the_schema() throws Exception {
        assertThat(validity()).as("absent before it is configured").isNull();
        restartEventLog(dataSource, FAST.withTenantIndexed(Set.of(TYPE)));
        assertThat(eventLog.tenantIndex().build(TYPE).get(60, TimeUnit.SECONDS)).isTrue();
        assertThat(validity()).isTrue();
        assertThat(eventLog.tenantIndex().valid(TYPE)).isTrue();
        assertThat((Object) eventLog.eventTypeIndex().build(TYPE)).as("the event-type index is not configured").isNull();
        assertThat(queryLong("SELECT count(*) FROM pg_indexes WHERE indexname = '" + tables.tenantIndex() + "' AND indexdef LIKE '%(tenant, global_order)%'")).isEqualTo(1);
    }

    @Test
    void where_the_engine_does_not_create_the_schema_the_tenant_index_is_not_built_and_its_statement_is_logged() throws Exception {
        var logger   = (Logger) LoggerFactory.getLogger(EventTypeIndex.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var settings = FAST.withTenantIndexed(Set.of(TYPE));
            var external = new EventLog(dataSource, SchemaOwnership.COMPONENT, EventLogSchema.lockedAggregateTypeDdl(dataSource), settings, EventLogObserver.NONE, () -> false);
            external.start();
            try {
                external.registerAggregateType(TYPE);
                assertThat((Object) external.tenantIndex().build(TYPE)).as("no build started").isNull();
                assertThat(external.tenantIndex().valid(TYPE)).isFalse();
            } finally {
                external.stop();
            }
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(validity()).as("absent").isNull();
        var create = EventLogSchemaContributor.tenantIndexStatements(tables).getFirst();
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(create));
    }

    // ---- helpers ----

    record Outcome(long held, Set<Long> missed, List<String> duplicatesOrForeign, List<String> orderViolations) {
    }

    private static void assertDeliveredOnce(Outcome outcome) {
        assertThat(outcome.missed()).as("events of the tenant not delivered").isEmpty();
        assertThat(outcome.duplicatesOrForeign()).as("duplicates or events of another tenant or type").isEmpty();
        assertThat(outcome.orderViolations()).isEmpty();
    }

    /**
     * A transaction holds an {@code X} of {@link #MINE} at {@code g0}; rounds of {@link #OTHER} rows (and, with
     * {@code withType}, {@code Y} rows of {@link #MINE}) and one {@code X} of {@link #MINE} commit above it; a warm
     * unfiltered {@code CONTIGUOUS} subscription keeps the reader past them. Then the tested subscription, filtered on {@link #MINE} (and
     * on {@code X}), starts at 1 - with {@code restarted}, after the reader restarted, so it attaches under the unknown
     * watermark, bridges and hands its gaps off before {@code g0} commits.
     */
    private Outcome heldEvent(ConsistencyMode mode, boolean withType, boolean restarted, Set<WatermarkMutant> mutants) throws Exception {
        restartEventLog(dataSource, FILTERED);
        // CONTIGUOUS keeps F4's wait after the restart and hands nothing off: the reader learns of g0 only from the
        // tested subscription's hand-off, so the bridge's handled range alone decides whether g0 is lost
        subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key("warm", TYPE, "node-1"), 1, ConsistencyMode.CONTIGUOUS, event -> {
        }, TestSeams.NONE));
        var committed = new TreeSet<Long>();
        committed.add(append("x-0", "X", MINE, 1));
        append("b-0", "X", OTHER, 50);
        var held = openAppend(AppendRequest.withoutExpectedOrder(TYPE, "x-held", events("X", 1)).withTenant(MINE));
        var last = 0L;
        for (var round = 0; round < 20; round++) {
            append("b-" + round % 7, "X", OTHER, 99);
            if (withType) {
                append("y-" + round % 5, "Y", MINE, 20);
            }
            last = append("x-" + round % 3, "X", MINE, 1);
            committed.add(last);
        }
        var head = last;
        awaitTrue(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE).state().cursor() >= head);
        if (restarted) {
            terminateReaderBackend();
            awaitTrue(() -> reads.detachReasons.contains("READER_RESTART") && eventLog.reader(TYPE) != null);
        }
        var recorder = new Recorder();
        var seams    = new TestSeams(mutants, TestSeams.Hooks.NONE, null);
        var key      = new ProgressStore.Key("tested", TYPE, "node-1");
        var spec     = mode == ConsistencyMode.FRESH ? Subscription.SubscriptionSpec.periodic(key, 1, mode, recorder, seams)
                                                     : Subscription.SubscriptionSpec.inTransaction(key, 1, mode, PageHandler.perEvent((e, tx) -> recorder.handle(e)), seams);
        if (withType) {
            spec = spec.withFilter(EventTypeFilter.of("X"));
        }
        var tested = subscribe(spec.withTenant(MINE));
        if (restarted) {
            var handing = !mutants.contains(WatermarkMutant.FILTERED_BRIDGE_MARKS_RANGE_UNDER_UNKNOWN_WATERMARK);
            awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED && (!handing || reads.handedOffBy.contains("tested")));
            assertThat(eventLog.reader(TYPE).state().watermark()).as("the restart's watermark is still unknown").isNull();
        }
        awaitTrue(tested::isIdle);
        awaitReads(3);
        awaitTrue(tested::isIdle);
        var g0 = held.globalOrder();
        held.commit();
        committed.add(g0);
        var tail = append("x-tail", "X", MINE, 1);
        committed.add(tail);
        var target = reads.reads.get() + 40;
        await().atMost(WAIT).until(() -> recorder.deliveredOrder(tail) && recorder.deliveredOrder(g0) || reads.reads.get() >= target);
        awaitReads(2);
        var missed = new TreeSet<>(committed);
        missed.removeAll(recorder.deliveredOrders());
        var wrong = new ArrayList<String>();
        var seen  = new HashSet<Long>();
        for (var event : recorder.delivered) {
            if (!MINE.equals(event.tenant()) || !event.eventType().equals("X") || !seen.add(event.globalOrder())) {
                wrong.add(event.globalOrder() + ":" + event.eventType() + ":" + event.tenant());
            }
        }
        return new Outcome(g0, missed, wrong, perAggregateOrderViolations(recorder.delivered));
    }

    /** Appends {@code count} events of {@code eventType} for {@code tenant} and commits; returns the first global order */
    private long append(String aggregateId, String eventType, String tenant, int count) throws SQLException {
        return appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events(eventType, count)).withTenant(tenant))
                .getFirst().events().getFirst().globalOrder();
    }

    /** @return {@code indisvalid AND indisready} of the tenant index, or {@code null} if it is absent */
    private Boolean validity() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.INDEX_VALIDITY)) {
            statement.setString(1, tables.tenantIndex());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getBoolean(1) : null;
            }
        }
    }
}
