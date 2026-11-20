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

import dk.trustworks.essentials.components.eventlog.EventLogStorage.TailStatements;
import dk.trustworks.essentials.components.eventlog.TailReader.*;
import org.junit.jupiter.api.Test;
import org.postgresql.PGStatement;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hole and range bookkeeping of {@link TailReader}: {@code recordGap}, {@code removeHole}, {@code retire},
 * {@code handOff} / {@code addUnresolvedRange} and {@link TailReader#watermark()}, driven through
 * {@link TailReader#apply(Read, boolean)} with synthetic reads. No database: the reader's connection is a JDK proxy that
 * answers the head statement and records what the hole read binds. Everything runs on the test thread, the reader's owner.
 */
class TailReaderTest {
    private static final int HEAD = 100;

    @Test
    void a_late_commit_inside_a_range_splits_or_trims_the_range_and_is_published_once() throws Exception {
        var reader = reader(HEAD, Set.of());

        // 101..109 is 9 values, above the threshold of 3: one range
        assertThat(reader.apply(read(110), false)).extracting(StoredEvent::globalOrder).containsExactly(110L);
        assertThat(reader.ranges()).containsExactly(Map.entry(101L, 109L));
        assertThat(reader.cursor()).isEqualTo(110);

        assertThat(reader.apply(read(105), false)).as("a late commit is published").extracting(StoredEvent::globalOrder).containsExactly(105L);
        assertThat(reader.ranges()).as("split in the middle").containsExactly(Map.entry(101L, 104L), Map.entry(106L, 109L));

        reader.apply(read(101), false);
        assertThat(reader.ranges()).as("trimmed at the low end").containsExactly(Map.entry(102L, 104L), Map.entry(106L, 109L));

        reader.apply(read(109), false);
        assertThat(reader.ranges()).as("trimmed at the high end").containsExactly(Map.entry(102L, 104L), Map.entry(106L, 108L));

        assertThat(reader.apply(read(105), false)).as("a value no longer a hole is not published again").isEmpty();
        assertThat(reader.ranges()).containsExactly(Map.entry(102L, 104L), Map.entry(106L, 108L));
        assertThat(reader.holes()).isEmpty();
        assertThat(reader.watermark()).as("below the lowest range").isEqualTo(101L);
    }

    @Test
    void a_retired_candidate_resolves_only_the_holes_and_ranges_at_or_below_it() throws Exception {
        var fake   = new FakeDb(HEAD, Set.of());
        var reader = reader(fake);

        // Hole 101 (gap of 1), candidate C1 at 102 waiting for x1
        reader.apply(read(Set.of("x1"), 102), false);
        assertThat(reader.holes()).containsExactly(101L);
        // Range 103..109 above C1, candidate C2 at 110 waiting for x1 and x2
        reader.apply(read(Set.of("x1", "x2"), 110), false);
        assertThat(reader.ranges()).containsExactly(Map.entry(103L, 109L));
        assertThat(reader.state().candidates()).extracting(Candidate::highestRead).containsExactly(102L, 110L);

        reader.apply(read(Set.of("x1", "x2")), false);
        assertThat(fake.holeReads).as("x1 still running: nothing retires").isEmpty();

        reader.apply(read(Set.of("x2")), false);
        assertThat(fake.holeReads).as("C1 retired: the hole read gets hole 101, no range").containsExactly(List.of(List.of(101L), List.of(), List.of()));
        assertThat(reader.holes()).isEmpty();
        assertThat(reader.ranges()).as("the range above C1 stays outstanding").containsExactly(Map.entry(103L, 109L));
        assertThat(reader.state().candidates()).extracting(Candidate::highestRead).containsExactly(110L);
        assertThat(reader.watermark()).isEqualTo(102L);

        reader.apply(read(Set.of()), false);
        assertThat(fake.holeReads).as("C2 retired: the hole read gets the range").hasSize(2);
        assertThat(fake.holeReads.get(1)).containsExactly(List.of(), List.of(103L), List.of(109L));
        assertThat(reader.ranges()).isEmpty();
        assertThat(reader.state().candidates()).isEmpty();
        assertThat(reader.watermark()).isEqualTo(110L);
    }

    @Test
    void overlapping_and_adjacent_hand_offs_merge_into_one_range_and_swallow_holes() throws Exception {
        // The reader starts with a writer running: an initial candidate at the head, watermark unknown
        var reader = reader(HEAD, Set.of("x1"));
        assertThat(reader.watermark()).as("unknown while the initial candidate is outstanding").isNull();

        assertThat(reader.handOff(List.of(new HandledSet.Gap(91, 92)))).isTrue();
        assertThat(reader.holes()).as("small gaps are holes").containsExactly(91L, 92L);
        assertThat(reader.ranges()).isEmpty();

        reader.handOff(List.of(new HandledSet.Gap(95, 98)));
        assertThat(reader.ranges()).containsExactly(Map.entry(95L, 98L));

        reader.handOff(List.of(new HandledSet.Gap(97, 200)));
        assertThat(reader.ranges()).as("overlap, and clamped to the head").containsExactly(Map.entry(95L, 100L));

        reader.handOff(List.of(new HandledSet.Gap(95, 100), new HandledSet.Gap(98, 99), new HandledSet.Gap(101, 105)));
        assertThat(reader.ranges()).as("a repeated hand-off, a gap inside a range and a gap above the head change nothing").containsExactly(Map.entry(95L, 100L));
        assertThat(reader.holes()).containsExactly(91L, 92L);

        reader.handOff(List.of(new HandledSet.Gap(90, 94)));
        assertThat(reader.ranges()).as("adjacent to the range: merged").containsExactly(Map.entry(90L, 100L));
        assertThat(reader.holes()).as("holes under the new range are dropped, never re-read twice").isEmpty();

        reader.apply(read(Set.of()), false);
        assertThat(reader.handOff(List.of(new HandledSet.Gap(50, 60)))).as("initial candidate retired: nothing held").isFalse();
        assertThat(reader.ranges()).doesNotContainKey(50L);
    }

    @Test
    void gaps_start_above_the_final_point_and_become_a_range_only_above_the_threshold() throws Exception {
        var reader = reader(HEAD, Set.of());

        reader.apply(read(102), false);
        assertThat(reader.holes()).as("101 is the first value above the head").containsExactly(101L);

        reader.apply(read(106), false);
        assertThat(reader.holes()).as("a gap of 3 is still holes").containsExactly(101L, 103L, 104L, 105L);
        assertThat(reader.ranges()).isEmpty();

        reader.apply(read(111), false);
        assertThat(reader.ranges()).as("a gap of 4 is a range").containsExactly(Map.entry(107L, 110L));
        assertThat(reader.holes()).containsExactly(101L, 103L, 104L, 105L);

        assertThat(reader.apply(read(90), false)).as("at or below the head: not a hole, not published").isEmpty();
        assertThat(reader.holes()).containsExactly(101L, 103L, 104L, 105L);
        assertThat(reader.cursor()).isEqualTo(111);
    }

    @Test
    void the_watermark_stays_below_pending_holes_and_rises_when_they_resolve() throws Exception {
        var reader = reader(HEAD, Set.of());
        assertThat(reader.watermark()).as("no gap: the head").isEqualTo(100L);

        reader.apply(read(103), false);
        assertThat(reader.holes()).containsExactly(101L, 102L);
        assertThat(reader.watermark()).as("below the lowest hole").isEqualTo(100L);

        reader.apply(read(102), false);
        assertThat(reader.watermark()).as("the lowest hole is still 101").isEqualTo(100L);
        reader.apply(read(101), false);
        assertThat(reader.watermark()).as("every hole resolved: the cursor").isEqualTo(103L);

        // Resolution by retirement: the watermark waits for the running writer to finish
        var retiring = reader(HEAD, Set.of());
        retiring.apply(read(Set.of("x1"), 103), false);
        retiring.apply(read(Set.of("x1")), false);
        assertThat(retiring.watermark()).as("writer x1 may still commit 101 or 102").isEqualTo(100L);
        retiring.apply(read(Set.of()), false);
        assertThat(retiring.holes()).isEmpty();
        assertThat(retiring.watermark()).isEqualTo(103L);
    }

    // ---- helpers

    private static TailReader reader(long head, Set<String> runningAtStart) throws SQLException {
        return reader(new FakeDb(head, runningAtStart));
    }

    private static TailReader reader(FakeDb fake) throws SQLException {
        var reader = new TailReader("Orders", fake.connection(), TailStatements.of("el_orders_events"), EventLogSettings.defaults().withHoleRangeThreshold(3), resultSet -> null,
                                    EventLogObserver.NONE, TestSeams.NONE, watermark -> {
        });
        reader.startAtHead();
        return reader;
    }

    /** A tail read of these rows, with no running set */
    private static Read read(long... globalOrders) {
        return new Read(events(globalOrders), null, 0, false, true);
    }

    /** A combined probe of these rows, with this running set */
    private static Read read(Set<String> running, long... globalOrders) {
        return new Read(events(globalOrders), new RunningSet(1, running), 0, true, true);
    }

    private static List<StoredEvent> events(long... globalOrders) {
        var events = new ArrayList<StoredEvent>();
        for (var g : globalOrders) {
            events.add(new StoredEvent("Orders", g, g, UUID.randomUUID(), null, null, "OrderEvent", 1, OffsetDateTime.now(), "order-1", "{}", "{}", null));
        }
        return events;
    }

    /**
     * Just enough JDBC for {@link TailReader}: the head statement returns {@code head} and the running set, the hole read
     * returns no rows and is recorded as its three array arguments, everything else does nothing.
     */
    private static final class FakeDb {
        final long                     head;
        final Set<String>              running;
        final List<List<List<Long>>>   holeReads = new ArrayList<>();
        private final String           headSql   = TailStatements.of("el_orders_events").head();
        private final String           holeSql   = TailStatements.of("el_orders_events").holeRead();

        FakeDb(long head, Set<String> running) {
            this.head = head;
            this.running = running;
        }

        Connection connection() {
            return proxy(Connection.class, (method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "createArrayOf" -> proxy(Array.class, (m, a) -> m.getName().equals("getArray") ? args[1] : defaultValue(m));
                default -> defaultValue(method);
            });
        }

        private PreparedStatement statement(String sql) {
            var bound = new TreeMap<Integer, List<Long>>();
            return statementProxy((method, args) -> {
                    return switch (method.getName()) {
                        case "setArray" -> {
                            bound.put((Integer) args[0], Arrays.stream(elementsOf((java.sql.Array) args[1])).map(Long.class::cast).toList());
                            yield null;
                        }
                        case "executeQuery" -> {
                            if (sql.equals(holeSql)) {
                                holeReads.add(new ArrayList<>(bound.values()));
                            }
                            yield resultSet(sql.equals(headSql));
                        }
                        default -> defaultValue(method);
                    };
            });
        }

        private ResultSet resultSet(boolean headRow) {
            var rows = new int[]{headRow ? 1 : 0};
            return proxy(ResultSet.class, (method, args) -> switch (method.getName()) {
                case "next" -> rows[0]-- > 0;
                case "getLong" -> head;
                case "getArray" -> running.isEmpty() ? null : proxy(Array.class, (m, a) -> {
                    if (!m.getName().equals("getArray")) {
                        return defaultValue(m);
                    }
                    var values = new ArrayList<Object>();
                    values.add("2");
                    values.addAll(running);
                    return values.toArray();
                });
                default -> defaultValue(method);
            });
        }
    }

    private static Object[] elementsOf(java.sql.Array array) {
        try {
            return (Object[]) array.getArray();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Handler {
        Object handle(Method method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(TailReaderTest.class.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> handler.handle(m, a)));
    }

    /** A {@code PreparedStatement} that also answers {@code unwrap(PGStatement.class)} with itself */
    private static PreparedStatement statementProxy(Handler handler) {
        return (PreparedStatement) (Proxy.newProxyInstance(TailReaderTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class, PGStatement.class},
                                                new InvocationHandler() {
                                                    @Override
                                                    public Object invoke(Object proxy, Method method, Object[] args) {
                                                        return method.getName().equals("unwrap") ? proxy : handler.handle(method, args);
                                                    }
                                                }));
    }

    private static Object defaultValue(Method method) {
        var type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
