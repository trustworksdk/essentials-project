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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Invariant I8 (design phase 9 §9.2): after the copy and the import, the event log holds every source event, and every
 * imported subscription delivers each event at or above its old resume point, including the event that filled one of
 * its open transient gaps before the cut-over.
 * <p>
 * The source is built through the old engine, with its {@code PostgresqlEventStreamGapHandler}: holes from rolled-back
 * appends, permanent gaps (one of which an in-flight append fills late), a sequence jump, and a durable subscriber that
 * polled past an in-flight append (an open transient gap) whose commit landed before the cut-over. Values of
 * {@code Orders}, in commit order of drawing:
 * <pre>
 *   1..6       two streams                          delivered to the old subscriber
 *   7          rolled back                          open transient gap, never filled
 *   8          rolled back                          permanent gap, never filled
 *   9          in flight, committed late            transient gap given up (permanent), then filled: lost by the old engine
 *   10..12     a stream                             delivered
 *   13         in flight, committed late            open transient gap, filled before the cut-over: the gap fill
 *   14..16     a stream                             delivered; R = 17
 *   (setval 1000)
 *   1001..1003 a stream after the jump              not yet delivered
 * </pre>
 * Expected import: {@code I = least(17, 13) = 13}. Run against another major with {@code -Dmigration.it.image=postgres:16.15}.
 */
class MigrationInvariantIT extends AbstractMigrationIT {
    private static final String ORDERS     = "Orders";
    private static final String PAYMENTS   = "Payments";
    private static final String OLD_ORDERS = "orders-projector";
    private static final String OLD_LEGACY = "payments-legacy";

    private static final long R         = 17;
    private static final long OPEN_HOLE = 7;
    private static final long GAP_FILL  = 13;

    /** One per mode and model, plus a per-instance arm with two instances */
    private record Arm(String subscriber, ConsistencyMode consistency, ProgressModel model, List<String> instances) {
        SubscriptionDefinition definition(String instance) {
            var builder = SubscriptionDefinition.builder(subscriber, ORDERS).consistency(consistency);
            return (instance.isEmpty() ? builder.exclusive() : builder.perInstance(instance)).build();
        }

        ImportedSubscription imported() {
            return instances.equals(List.of("")) ? ImportedSubscription.exclusive(OLD_ORDERS, definition(""), model)
                                                 : ImportedSubscription.perInstance(OLD_ORDERS, definition(instances.getFirst()), model, instances);
        }
    }

    private static final List<Arm> ARMS = List.of(new Arm("fresh-periodic", ConsistencyMode.FRESH, ProgressModel.PERIODIC, List.of("")),
                                                  new Arm("fresh-page", ConsistencyMode.FRESH, ProgressModel.IN_HANDLER_TRANSACTION, List.of("")),
                                                  new Arm("contiguous-periodic", ConsistencyMode.CONTIGUOUS, ProgressModel.PERIODIC, List.of("")),
                                                  new Arm("contiguous-page", ConsistencyMode.CONTIGUOUS, ProgressModel.IN_HANDLER_TRANSACTION, List.of("")),
                                                  new Arm("per-instance", ConsistencyMode.FRESH, ProgressModel.PERIODIC, List.of("node-a", "node-b")));

    private OldEngine         old;
    private ResumePointImport importStep;
    private Migration         migration;

    // ------------------------------------------------------------------ the source

    private void source(MigrationMutant... mutants) throws Exception {
        old = new OldEngine(true, IdentifierColumnType.UUID, JSONColumnType.JSONB, ORDERS, PAYMENTS);
        var gaps = old.gapHandler;
        old.streams(ORDERS, 2, 3);                                                  // 1..6
        old.burn(ORDERS, 1);                                                        // 7
        old.burn(ORDERS, 1);                                                        // 8
        old.factory.usingUnitOfWork(uow -> gaps.registerPermanentGaps(AggregateType.of(ORDERS), List.of(GlobalEventOrder.of(8)), "rolled back"));
        var lateForPermanent = old.held(ORDERS);                                    // 9, in flight
        lateForPermanent.awaitInserted();
        old.streams(ORDERS, 1, 3);                                                  // 10..12
        var gapFill = old.held(ORDERS);                                             // 13, in flight
        gapFill.awaitInserted();
        old.streams(ORDERS, 1, 3);                                                  // 14..16

        // The old subscriber polls past the in-flight appends: its gap handler records them as open transient gaps
        var subscriber = SubscriberId.of(OLD_ORDERS);
        var seen = old.eventStore.pollEvents(AggregateType.of(ORDERS), 1L, Optional.of(100), Optional.of(Duration.ofMillis(20)), Optional.empty(),
                                             Optional.of(subscriber), Optional.empty())
                                 .map(e -> e.globalEventOrder().longValue())
                                 .takeUntil(g -> g == R - 1)
                                 .collectList()
                                 .block(Duration.ofSeconds(30));
        assertThat(seen).as("delivered to the old subscriber").containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 10L, 11L, 12L, 14L, 15L, 16L);
        // It gives up on 9 (promoted to a permanent gap), and saves its resume point
        old.factory.usingUnitOfWork(uow -> gaps.gapHandlerFor(subscriber).giveUpTransientGaps(AggregateType.of(ORDERS), List.of(GlobalEventOrder.of(9))));
        var resumePoints = new PostgresqlDurableSubscriptionRepository(old.jdbi, old.eventStore);
        var point        = resumePoints.createResumePoint(subscriber, AggregateType.of(ORDERS), GlobalEventOrder.of(1));
        resumePoints.saveResumePoints(List.of(point.setResumeFromAndIncluding(GlobalEventOrder.of(R))));
        resumePoints.createResumePoint(SubscriberId.of(OLD_LEGACY), AggregateType.of(PAYMENTS), GlobalEventOrder.of(2));

        // Both late appends commit before the cut-over
        lateForPermanent.releaseAndJoin();
        gapFill.releaseAndJoin();
        assertThat(longs("SELECT gap_global_event_order FROM transient_subscriber_gaps WHERE subscriber_id = '" + OLD_ORDERS + "' ORDER BY 1"))
                .as("open transient gaps").containsExactly(OPEN_HOLE, GAP_FILL);
        assertThat(longs("SELECT gap_global_event_order FROM permanent_gaps WHERE aggregate_type = '" + ORDERS + "' ORDER BY 1")).as("permanent gaps").containsExactly(8L, 9L);
        assertThat(queryLong("SELECT resume_from_and_including_global_eventorder FROM durable_subscriptions WHERE subscriber_id = '" + OLD_ORDERS + "'")).isEqualTo(R);

        old.streams(PAYMENTS, 2, 2);
        old.burn(PAYMENTS, 1);
        old.stream(PAYMENTS, 1);

        var builder = ResumePointImport.builder(eventLog.admin());
        ARMS.forEach(arm -> builder.addSubscription(arm.imported()));
        importStep = builder.build();
        importStep.seams(mutants(mutants));
        migration = new Migration(new MigrationPlan(dataSource, eventLog, List.of(SourceTable.of(ORDERS, old.table(ORDERS)), SourceTable.of(PAYMENTS, old.table(PAYMENTS))),
                                                    CopySettings.defaults().withBatchSize(4), List.of(importStep)));
        migration.seams(mutants(mutants));
        migration.prepare();
        migration.copyEpoch();

        // A sequence jump, and events above it the old subscriber never saw; copied by the cut-over
        execute("SELECT setval(pg_get_serial_sequence('" + old.table(ORDERS) + "', 'global_order'), 1000)");
        old.stream(ORDERS, 3);                                                      // 1001..1003
        assertThat(oldMax(old, ORDERS)).isEqualTo(1003);
    }

    // ------------------------------------------------------------------ the new engine

    /**
     * Subscribes every arm on a fresh event log node, appends {@code newEvents} events, and waits until each arm has
     * delivered them (delivery is in global order, so everything below them was offered first)
     *
     * @return per arm and instance, the global orders delivered
     */
    private Map<String, Set<Long>> deliver(int newEvents) throws Exception {
        var delivered = new ConcurrentHashMap<String, Set<Long>>();
        var node      = EventLog.builder().setDataSource(dataSource).build();
        node.start();
        node.registerAggregateType(ORDERS);
        var handles = new ArrayList<SubscriptionHandle>();
        try {
            for (var arm : ARMS) {
                for (var instance : arm.instances()) {
                    var set = ConcurrentHashMap.<Long>newKeySet();
                    delivered.put(arm.subscriber() + "/" + instance, set);
                    handles.add(arm.model() == ProgressModel.PERIODIC
                                ? node.subscribe(arm.definition(instance), (PeriodicHandler) event -> set.add(event.globalOrder()))
                                : node.subscribe(arm.definition(instance), (PageHandler) (page, transaction) -> page.forEach(e -> set.add(e.globalOrder()))));
                }
            }
            var appended = new ArrayList<Long>();
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                for (var i = 0; i < newEvents; i++) {
                    node.append(connection, AppendRequest.withoutExpectedOrder(ORDERS, "after-cutover-" + i,
                                                                                            List.of(new EventToAppend(UUID.randomUUID(), null, null, "NewEvent", 1, Instant.now(), "{}", "{}"))));
                    connection.commit();
                    appended.add(queryLong("SELECT max(global_order) FROM " + AggregateTypeTables.of(ORDERS).eventTable()));
                }
            }
            await(() -> delivered.values().stream().allMatch(s -> s.containsAll(appended)), Duration.ofSeconds(30));
            assertThat(delivered).allSatisfy((key, set) -> assertThat(set).as("%s delivered the new events", key).containsAll(appended));
            return delivered;
        } finally {
            handles.forEach(SubscriptionHandle::stop);
            node.stop();
        }
    }

    /** I8's delivery: every source event at or above R, the gap fill, every new event; nothing below the imported I */
    private void assertI8Delivery(Map<String, Set<Long>> delivered) throws SQLException {
        var eventTable = AggregateTypeTables.of(ORDERS).eventTable();
        var atOrAboveR = longs("SELECT global_order FROM " + eventTable + " WHERE global_order >= " + R);
        assertThat(atOrAboveR).as("source events at or above R, and the new ones").contains(1001L, 1002L, 1003L).hasSizeGreaterThanOrEqualTo(4);
        for (var entry : delivered.entrySet()) {
            assertThat(entry.getValue()).as("%s: every event at or above the old resume point", entry.getKey()).containsAll(atOrAboveR);
            assertThat(entry.getValue()).as("%s: the gap fill committed before the cut-over", entry.getKey()).contains(GAP_FILL);
        }
    }

    // ------------------------------------------------------------------ the tests

    @Test
    void every_imported_subscription_delivers_every_event_from_its_old_resume_point_and_the_gap_fill() throws Exception {
        source();
        var cutover = migration.cutover();
        assertThat(cutover.types()).extracting(CutoverReport.TypeCutover::aggregateType).containsExactly(ORDERS, PAYMENTS);

        // Equal per aggregate type (§6.1): every source event, same global order, event order, id and payload bytes
        for (var type : List.of(ORDERS, PAYMENTS)) {
            assertThat(differingRows(old, type, oldMax(old, type))).as("rows of %s that differ", type).isZero();
            assertThat(newCount(type)).as("rows of %s", type).isEqualTo(queryLong("SELECT count(*) FROM " + old.table(type)));
            assertHeadsConsistent(type);
        }
        assertThat(migration.verifyAll().equal()).isTrue();

        // The report
        var report = importStep.report().orElseThrow();
        assertThat(report.gapTracking()).isTrue();
        assertThat(report.imported()).hasSize(ARMS.size()).allSatisfy(i -> {
            assertThat(i.oldResumeFrom()).isEqualTo(R);
            assertThat(i.resumeFrom()).as("I = least(R, lowest filled open gap)").isEqualTo(GAP_FILL);
            assertThat(i.replayValues()).isEqualTo(R - GAP_FILL);
            assertThat(i.replayRows()).as("13..16").isEqualTo(4);
            assertThat(i.openTransientGaps()).isEqualTo(2);
            assertThat(i.filledOpenTransientGaps()).isEqualTo(1);
            assertThat(i.lateFilledPermanentGapsBelowResume()).as("9: given up, filled late, reported only").isEqualTo(1);
        });
        assertThat(report.imported()).filteredOn(i -> i.subscriber().equals("per-instance")).singleElement()
                                     .satisfies(i -> assertThat(i.instanceNames()).containsExactly("node-a", "node-b"));
        assertThat(report.unmapped()).containsExactly(new ImportReport.UnmappedSubscriber(OLD_LEGACY, PAYMENTS, 2L));

        // The progress rows, as the engine stores them
        for (var arm : ARMS) {
            for (var instance : arm.instances()) {
                assertThat(queryLong("SELECT resume_from FROM el_progress WHERE subscriber = '" + arm.subscriber() + "' AND instance_name = '" + instance + "'"))
                        .as("%s/%s", arm.subscriber(), instance).isEqualTo(GAP_FILL);
                assertThat(queryLong("SELECT count(*) FROM el_progress WHERE subscriber = '" + arm.subscriber() + "' AND instance_name = '" + instance +
                                             "' AND progress_model = '" + arm.model() + "' AND consistency_mode = '" + arm.consistency() + "' AND lease_owner IS NULL"))
                        .isEqualTo(1);
            }
        }

        var delivered = deliver(2);
        assertI8Delivery(delivered);
        assertThat(delivered).allSatisfy((key, set) -> assertThat(set).as("%s: nothing below I", key).allMatch(g -> g >= GAP_FILL));
    }

    @Test
    void must_fail_IMPORT_RESUME_UNCHANGED_loses_the_gap_fill() throws Exception {
        // With the progress-row checks: the cut-over refuses, and rolls back
        source(IMPORT_RESUME_UNCHANGED);
        assertThatThrownBy(migration::cutover).isInstanceOf(IllegalStateException.class).hasMessageContaining("above open transient gap " + GAP_FILL);
        assertThat(migration.status(ORDERS).orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
        assertThat(queryLong("SELECT count(*) FROM el_progress")).isZero();

        // Without them (CUTOVER_WITHOUT_VERIFY), the import commits I = R, and I8 fails: the gap fill is never delivered
        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
        eventLog.stop();
        eventLog = EventLog.builder().setDataSource(dataSource).build();
        eventLog.start();
        source(IMPORT_RESUME_UNCHANGED, CUTOVER_WITHOUT_VERIFY);
        migration.cutover();
        assertThat(importStep.report().orElseThrow().imported()).allSatisfy(i -> assertThat(i.resumeFrom()).isEqualTo(R));
        var delivered = deliver(2);
        assertThatThrownBy(() -> assertI8Delivery(delivered)).as("I8 must fail").isInstanceOf(AssertionError.class).hasMessageContaining("gap fill");
        assertThat(delivered).allSatisfy((key, set) -> assertThat(set).doesNotContain(GAP_FILL));
    }

    @Test
    void IMPORT_LOWEST_GAP_UNFILTERED_only_replays_more() throws Exception {
        source(IMPORT_LOWEST_GAP_UNFILTERED);
        migration.cutover();
        assertThat(importStep.report().orElseThrow().imported()).allSatisfy(i -> {
            assertThat(i.resumeFrom()).as("the lowest open gap, though it never filled").isEqualTo(OPEN_HOLE);
            assertThat(i.replayValues()).isEqualTo(R - OPEN_HOLE);
            assertThat(i.replayRows()).as("9..16, against 4 under the filled-gap rule").isEqualTo(8);
        });
        assertI8Delivery(deliver(1));
    }

    @Test
    void an_existing_progress_row_with_another_resume_point_is_refused_and_an_equal_one_is_a_rerun() throws Exception {
        source();
        var arm = ARMS.getFirst();
        try (var connection = dataSource.getConnection()) {
            eventLog.admin().importProgress(arm.definition(""), arm.model(), "", 5, connection);
        }
        assertThatThrownBy(migration::cutover).isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists with resume point 5");
        assertThat(migration.status(ORDERS).orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
        assertThat(queryLong("SELECT count(*) FROM el_progress")).as("only the planted row").isEqualTo(1);

        execute("UPDATE el_progress SET resume_from = " + GAP_FILL);
        migration.cutover();
        assertThat(queryLong("SELECT count(*) FROM el_progress WHERE resume_from = " + GAP_FILL)).isEqualTo(6);
    }

    @Test
    void a_mapping_without_an_old_resume_point_is_refused() throws Exception {
        source();
        var other = ResumePointImport.builder(eventLog.admin())
                                     .addSubscription(ImportedSubscription.exclusive("no-such-subscriber", SubscriptionDefinition.builder("x", ORDERS).build(),
                                                                                     ProgressModel.PERIODIC))
                                     .build();
        var plan = new MigrationPlan(dataSource, eventLog, List.of(SourceTable.of(ORDERS, old.table(ORDERS)), SourceTable.of(PAYMENTS, old.table(PAYMENTS))),
                                     CopySettings.defaults(), List.of(other));
        assertThatThrownBy(() -> new Migration(plan).cutover()).isInstanceOf(IllegalStateException.class).hasMessageContaining("has no row in 'durable_subscriptions'");
        assertThat(migration.status(ORDERS).orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
    }

    private List<Long> longs(String sql) throws SQLException {
        var result = new ArrayList<Long>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(resultSet.getLong(1));
            }
        }
        return result;
    }
}
