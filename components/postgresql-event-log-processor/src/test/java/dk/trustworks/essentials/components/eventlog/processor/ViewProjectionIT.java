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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.ProcessorTestBridge.CrashPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionStatus;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * Gate row I14, and I12 with application-sourced pages, through {@link ViewProjection},
 * each arm with the JDBI unit-of-work source and the Spring source, each must-fail variant seen to fail.
 */
class ViewProjectionIT extends AbstractProcessorIT {
    private static final long X = 37;

    private ViewProjection projection(SourceKind kind, Set<ProcessorMutant> mutants, ProcessorSeams seams, Ownership ownership, ViewProjection.PageApplier applier) {
        return ViewProjection.builder("order-totals", ORDERS)
                             .onPage(applier)
                             .ownership(ownership)
                             .errors(errors())
                             .transactions(source(kind, mutants))
                             .seams(seams)
                             .build(eventLog, serializer);
    }

    private static ViewProjection.PageApplier applying(Failure failure) {
        return (events, context) -> {
            for (var event : events) {
                failure.before(event, context);
                applyToView(context.connection(), event);
            }
        };
    }

    @FunctionalInterface
    private interface Failure {
        void before(DeliveredEvent event, ProjectionContext context) throws Exception;
    }

    // ---- (a) a handler failure once: the page shrinks, the event succeeds, everything applied once ----

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void a_page_failing_once_shrinks_and_every_event_is_applied_once(SourceKind kind) throws Exception {
        armOnce(kind, Set.of(), Set.of());
    }

    @Test
    void must_fail_a_PROCESSOR_SWALLOWS_FAILURE() {
        assertThatThrownBy(() -> armOnce(SourceKind.JDBI, Set.of(ProcessorMutant.PROCESSOR_SWALLOWS_FAILURE), Set.of())).isInstanceOf(AssertionError.class);
    }

    @Test
    void must_fail_a_NO_SHRINK() {
        assertThatThrownBy(() -> armOnce(SourceKind.JDBI, Set.of(), Set.of("NO_SHRINK"))).isInstanceOf(AssertionError.class);
    }

    private void armOnce(SourceKind kind, Set<ProcessorMutant> mutants, Set<String> engineMutants) throws Exception {
        appendEvents(-1);
        var failed     = new AtomicBoolean();
        var pageSizeOk = new CopyOnWriteArrayList<Integer>();
        var projection = projection(kind, mutants, seams(mutants, engineMutants), Ownership.exclusive(), (events, context) -> {
            for (var event : events) {
                if (event.globalOrder().longValue() == X && failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("fails once");
                }
                applyToView(context.connection(), event);
                if (event.globalOrder().longValue() == X) {
                    pageSizeOk.add(events.size());
                }
            }
        });
        projection.start();
        try {
            awaitCaughtUp(projection.key());
            assertThat(failed).isTrue();
            assertThat(pageSizeOk).as("the event succeeded in a shrunk page").isNotEmpty().allMatch(size -> size < EVENTS);
            assertThat(view()).isEqualTo(fold(Set.of()));
            assertThat(applied()).hasSize(EVENTS).allSatisfy((order, times) -> assertThat(times).as("applied once: " + order).isEqualTo(1));
            assertThat(parked(projection.key())).isEmpty();
        } finally {
            projection.stop();
        }
    }

    private static final ViewProjection.Truncate TRUNCATE = connection -> {
        try (var statement = connection.createStatement()) {
            statement.execute("TRUNCATE view_totals, applied");
        }
    };

    private static ViewProjection.PageApplier applyingAll() {
        return (events, context) -> {
            for (var event : events) {
                applyToView(context.connection(), event);
            }
        };
    }

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void a_rebuild_of_a_running_projection_clears_a_stale_row_replays_and_runs_again(SourceKind kind) throws Exception {
        appendEvents(-1);
        var projection = projection(kind, Set.of(), ProcessorSeams.NONE, Ownership.perInstance("node-1"), applyingAll());
        projection.start();
        try {
            awaitCaughtUp(projection.key());
            execute("INSERT INTO view_totals VALUES ('stale', 1)");

            assertThat(projection.rebuild(TRUNCATE)).isTrue();

            assertThat(projection.isRunning()).as("restarted after the rebuild").isTrue();
            awaitCaughtUp(projection.key());
            assertThat(view()).as("the replayed view, without the stale row").isEqualTo(fold(Set.of()));
            assertThat(applied()).hasSize(EVENTS).allSatisfy((order, times) -> assertThat(times).as("applied once since the rebuild: " + order).isEqualTo(1));
        } finally {
            projection.stop();
        }
    }

    @Test
    void a_rebuild_of_a_never_started_projection_returns_false_and_rolls_the_truncate_back() throws Exception {
        var projection = projection(SourceKind.JDBI, Set.of(), ProcessorSeams.NONE, Ownership.perInstance("node-1"), applyingAll());
        execute("INSERT INTO view_totals VALUES ('kept', 1)");

        assertThat(projection.rebuild(TRUNCATE)).as("no progress row to reset").isFalse();

        assertThat(view()).as("the truncate rolled back").isEqualTo(Map.of("kept", 1L));
        assertThat(projection.isRunning()).as("never started, so not started by the rebuild").isFalse();
    }

    @Test
    void an_engine_sourced_projection_rebuilds_only_through_a_data_source() throws Exception {
        appendEvents(-1);
        var projection = ViewProjection.builder("order-totals", ORDERS)
                                       .onPage(applyingAll())
                                       .ownership(Ownership.perInstance("node-1"))
                                       .errors(errors())
                                       .build(eventLog, serializer);
        projection.start();
        try {
            awaitCaughtUp(projection.key());
            assertThatThrownBy(() -> projection.rebuild(TRUNCATE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("rebuild(DataSource, truncate)");

            execute("INSERT INTO view_totals VALUES ('stale', 1)");
            assertThat(projection.rebuild(pool, TRUNCATE)).isTrue();

            assertThat(projection.isRunning()).isTrue();
            awaitCaughtUp(projection.key());
            assertThat(view()).isEqualTo(fold(Set.of()));
        } finally {
            projection.stop();
        }
    }

    @Test
    void a_rebuild_whose_truncate_fails_rolls_back_and_restarts_the_projection() throws Exception {
        appendEvents(-1);
        var projection = projection(SourceKind.JDBI, Set.of(), ProcessorSeams.NONE, Ownership.perInstance("node-1"), (events, context) -> {
            for (var event : events) {
                applyToView(context.connection(), event);
            }
        });
        projection.start();
        try {
            awaitCaughtUp(projection.key());

            assertThatThrownBy(() -> projection.rebuild(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("TRUNCATE view_totals, applied");
                }
                connection.commit(); // refused: the clear must commit with the reset or not at all
            })).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("must not call Connection.commit");

            assertThat(view()).as("the clear rolled back").isEqualTo(fold(Set.of()));
            assertThat(projection.isRunning()).as("a projection that ran is restarted after a failed rebuild").isTrue();
        } finally {
            projection.stop();
        }
    }

    // ---- (b) always failing: parked after retryAttempts + 1, its aggregate held, the others applied ----

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void b_an_event_always_failing_is_parked_and_its_aggregate_held(SourceKind kind) throws Exception {
        armAlways(kind, Set.of(), -1);
    }

    @Test
    void must_fail_b_PROCESSOR_SWALLOWS_FAILURE() {
        assertThatThrownBy(() -> armAlways(SourceKind.JDBI, Set.of(ProcessorMutant.PROCESSOR_SWALLOWS_FAILURE), -1)).isInstanceOf(AssertionError.class);
    }

    // ---- (c) a payload that cannot be deserialized: parked as (b) ----

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void c_an_event_that_cannot_be_deserialized_is_parked(SourceKind kind) throws Exception {
        armAlways(kind, Set.of(), X);
    }

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void b_a_connection_error_the_handler_throws_is_retried_then_parked_like_any_failure(SourceKind kind) throws Exception {
        // the page connection is fine, so the handler's own I/O-classified failure is not the engine's I/O failure
        armAlways(kind, Set.of(), -1, () -> new IllegalStateException("calling out failed", new SQLTransientConnectionException("the handler's own connection", "08006")));
    }

    private void armAlways(SourceKind kind, Set<ProcessorMutant> mutants, long unknownAt) throws Exception {
        armAlways(kind, mutants, unknownAt, () -> new IllegalStateException("always fails"));
    }

    /**
     * @param unknownAt {@code X} for arm (c): its payload class does not exist; else the handler always fails on {@code X}
     * @param failure   what the handler throws on {@code X}
     */
    private void armAlways(SourceKind kind, Set<ProcessorMutant> mutants, long unknownAt, Supplier<RuntimeException> failure) throws Exception {
        appendEvents(unknownAt);
        var projection = projection(kind, mutants, seams(mutants, Set.of()), Ownership.perInstance("node-1"), applying((event, context) -> {
            if (event.globalOrder().longValue() == X) {
                throw failure.get();
            }
        }));
        projection.start();
        try {
            var key = projection.key();
            awaitTrue("the head parked and the subscription past every event", Duration.ofSeconds(10),
                      () -> resumePoint(key) > EVENTS && parked(key).stream().anyMatch(row -> row.globalOrder() == X));
            var rows = parked(key);
            var head = rows.stream().filter(row -> row.globalOrder() == X).findFirst().orElseThrow();
            assertThat(head.head()).isTrue();
            assertThat(head.attempts()).isEqualTo(errors().retryAttempts() + 1);
            assertThat(head.errorMessage()).isPresent();
            if (unknownAt == X) {
                assertThat(head.errorType().orElseThrow() + head.errorMessage().orElseThrow()).containsIgnoringCase("Gone");
            }
            var tail = tailOf(X);
            assertThat(rows).extracting(ParkedRow::globalOrder).containsExactlyInAnyOrderElementsOf(tail);
            assertThat(rows).filteredOn(row -> row.globalOrder() != X).allSatisfy(row -> assertThat(row.blockedBy()).hasValue(X));
            assertThat(view()).isEqualTo(fold(tail));
            assertThat(applied()).hasSize(EVENTS - tail.size()).allSatisfy((order, times) -> assertThat(times).isEqualTo(1));
        } finally {
            projection.stop();
        }
    }

    // ---- (d) the handler commits through its typed resource: refused, no output without its progress ----

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void d_a_handler_committing_through_its_resource_fails_the_page(SourceKind kind) throws Exception {
        armCommits(kind, Set.of());
    }

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void must_fail_d_HANDLER_COMMITS_EARLY(SourceKind kind) {
        assertThatThrownBy(() -> armCommits(kind, Set.of(ProcessorMutant.HANDLER_COMMITS_EARLY))).isInstanceOf(AssertionError.class);
    }

    private void armCommits(SourceKind kind, Set<ProcessorMutant> mutants) throws Exception {
        appendEvents(-1);
        var tried     = new AtomicBoolean();
        var committed = new AtomicBoolean();
        var crashed   = new AtomicBoolean();
        var seams = new ProcessorSeams(mutants, (log, definition, handler, source) ->
                ProcessorTestBridge.subscribe(log, definition, handler, source, Set.of(), CrashPoint.AFTER_HANDLER_BEFORE_PROGRESS, committed::get, crashed));
        var projection = projection(kind, mutants, seams, Ownership.perInstance("node-1"), applying((event, context) -> {
            if (event.globalOrder().longValue() == X && tried.compareAndSet(false, true)) {
                switch (kind) {
                    case JDBI -> context.handle().commit();
                    case SPRING -> transactionManager.commit(context.resource(TransactionStatus.class));
                }
                committed.set(true);
            }
        }));
        var handle = projection.start().handle().orElseThrow();
        if (crashed.get() || waitBriefly(crashed)) {
            ProcessorTestBridge.cleanUpCrash(handle, this::execute);
            handle.stop();
            projection = projection(kind, mutants, ProcessorSeams.NONE, Ownership.perInstance("node-1"), applying((event, context) -> {
            }));
            projection.start();
        }
        try {
            awaitCaughtUp(projection.key());
            assertThat(tried).isTrue();
            assertThat(view()).isEqualTo(fold(Set.of()));
            assertThat(applied()).hasSize(EVENTS).allSatisfy((order, times) -> assertThat(times).as("applied once: " + order).isEqualTo(1));
        } finally {
            projection.stop();
        }
    }

    /** @return whether {@code crashed} became true within a few seconds (the crash, if any, happens on the first pages) */
    private static boolean waitBriefly(AtomicBoolean crashed) throws InterruptedException {
        for (var i = 0; i < 150 && !crashed.get(); i++) {
            Thread.sleep(20);
        }
        return crashed.get();
    }

    // ---- (e) a crash at every step of an application-sourced page ----

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void e_a_crash_at_every_step_of_a_page_keeps_the_view_equal_to_the_fold(SourceKind kind) throws Exception {
        for (var point : CrashPoint.values()) {
            armCrash(kind, point, Set.of());
        }
    }

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void must_fail_e_PROGRESS_OUTSIDE_PAGE_TRANSACTION(SourceKind kind) {
        // the engine's PROGRESS_AFTER_COMMIT: the page commits, then the progress is written on a connection of its own
        assertThatThrownBy(() -> armCrash(kind, CrashPoint.AFTER_HANDLER_BEFORE_PROGRESS, Set.of("PROGRESS_AFTER_COMMIT"))).isInstanceOf(AssertionError.class);
    }

    private void armCrash(SourceKind kind, CrashPoint point, Set<String> engineMutants) throws Exception {
        setUpRound();
        appendEvents(-1);
        var crashed    = new AtomicBoolean();
        var seams      = seams(Set.of(), engineMutants, point, crashed);
        var projection = projection(kind, Set.of(), seams, Ownership.perInstance("node-1"), applying((event, context) -> {
        }));
        var handle = projection.start().handle().orElseThrow();
        awaitTrue("crash at " + point, Duration.ofSeconds(10), crashed::get);
        ProcessorTestBridge.cleanUpCrash(handle, this::execute);
        handle.stop();
        var restarted = projection(kind, Set.of(), ProcessorSeams.NONE, Ownership.perInstance("node-1"), applying((event, context) -> {
        }));
        restarted.start();
        try {
            awaitCaughtUp(restarted.key());
            assertThat(view()).as("after a crash at " + point).isEqualTo(fold(Set.of()));
            assertThat(applied()).as("after a crash at " + point).hasSize(EVENTS).allSatisfy((order, times) -> assertThat(times).isEqualTo(1));
        } finally {
            restarted.stop();
        }
    }

    /** Each crash round starts from an empty log and view under a fresh event log */
    private void setUpRound() throws Exception {
        tearDown();
        setUpDatabase();
    }
}
