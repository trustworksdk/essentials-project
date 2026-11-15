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

import org.postgresql.PGStatement;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.eventlog.EventLogStorage.RUNNING_COLUMN;
import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The tail reader of one aggregate type (plan §4, §6; design §3.3). Owns one connection for its lifetime and is
 * touched by one thread only: cursor, hole set, candidates and watermark are plain fields.
 * <ul>
 *     <li><b>Cursor</b>: the highest {@code global_order} read. Every gap below it is a <b>hole</b> - a single value,
 *     or a range when a sequence jump left more than {@link EventLogSettings#holeRangeThreshold()} values.</li>
 *     <li>While a hole or candidate is outstanding, or the last read revealed a new hole, each read is the
 *     <b>combined probe</b>: tail above the cursor, outstanding holes re-read by {@code = ANY} and, while a range is
 *     outstanding, by a range join ({@code UNION ALL}, never {@code OR}: F1 with P0.11's form), and the running-writer
 *     set, read during execution after the statement's snapshot (§4.10, P0.2, P0.12) - also when no event row comes
 *     back. Otherwise the plain tail read.</li>
 *     <li>A probe creates a {@link Candidate} {@code (cursor, running set)} when a hole lies above every outstanding
 *     candidate, and retires each candidate whose set is disjoint from its running set. A retired candidate resolves
 *     every hole at or below it: those are re-read once ({@code holeRead}, §4.2) and the rows published before the
 *     watermark moves past them (F2).</li>
 *     <li><b>Watermark</b> (F2): {@code min(cursor, lowest unresolved hole - 1)}.</li>
 *     <li><b>Start</b> (F4): the reader starts at {@code resumeFrom - 1}, which the progress formula guarantees final,
 *     and reads the head and the running set in one statement. If any writer is running, an <b>initial candidate</b>
 *     {@code (head, running set)} covers every gap the reader then finds up to the head, and the watermark is unknown
 *     until it retires. With no writer running, gaps up to the head are final at once.</li>
 * </ul>
 */
final class TailReader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TailReader.class);

    /**
     * Statements that fail the generic-plan check (not index-only under {@code EXPLAIN (GENERIC_PLAN)}) and so run
     * unnamed, planned custom on every execution (P0.16). {@code EventLogGenericPlanIT} checks every other statement.
     */
    static final Set<String> CUSTOM_PLAN_STATEMENTS = Set.of();

    @FunctionalInterface
    interface RowMapper {
        StoredEvent map(ResultSet resultSet) throws SQLException;
    }

    private final String                          aggregateType;
    private final DataSource                      dataSource;
    private final EventLogStorage.TailStatements  statements;
    private final EventLogSettings                settings;
    private final RowMapper                       mapper;
    private final EventLogObserver                observer;
    private final TestSeams                       seams;

    private Connection                            connection;
    private final Map<String, PreparedStatement>  prepared   = new HashMap<>();
    private long                                  cursor;
    private final TreeSet<Long>                   holes      = new TreeSet<>();
    private final TreeMap<Long, Long>             ranges     = new TreeMap<>();
    private final TreeSet<Long>                   pending    = new TreeSet<>();
    private final TreeMap<Long, Long>             pendingRanges = new TreeMap<>();
    private final List<Candidate>                 candidates = new ArrayList<>();
    private boolean                               initialized;
    private long                                  finalUpTo;
    private boolean                               lastReadContiguous;
    private long                                  lastReadNanos;

    TailReader(String aggregateType, DataSource dataSource, EventLogStorage.TailStatements statements, EventLogSettings settings, RowMapper mapper,
               EventLogObserver observer, TestSeams seams) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.statements = requireNonNull(statements, "No statements provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.mapper = requireNonNull(mapper, "No mapper provided");
        this.observer = requireNonNull(observer, "No observer provided");
        this.seams = requireNonNull(seams, "No seams provided");
    }

    /**
     * (Re)start at {@code resumeFrom}: every value below it must be final - as a resume point saved by §4.7's formula
     * guarantees. Opens the reader's connection and forgets all earlier state.
     */
    void start(long resumeFrom) throws SQLException {
        close();
        connection = dataSource.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("SET jit = off");
            if (settings.genericPlans()) {
                statement.execute("SET plan_cache_mode = force_generic_plan");
            }
        }
        // The probe must run in autocommit (P0.2); the mutant keeps one transaction across probes
        connection.setAutoCommit(!seams.has(CACHED_STAT_ACTIVITY));
        cursor = resumeFrom - 1;
        holes.clear();
        ranges.clear();
        pending.clear();
        pendingRanges.clear();
        candidates.clear();
        lastReadContiguous = false;

        long      head;
        Set<String> running;
        try (var resultSet = statement("head", statements.head()).executeQuery()) {
            resultSet.next();
            head = resultSet.getLong(1);
            running = xids(resultSet.getArray(2));
        }
        if (seams.has(RESTARTED_READER_AT_HEAD) || running.isEmpty()) {
            // No writer can still commit at or below the head: every gap up to it is final
            initialized = true;
            finalUpTo = Math.max(cursor, head);
        } else {
            initialized = false;
            finalUpTo = cursor;
            candidates.add(new Candidate(head, running, true, System.nanoTime()));
        }
        publishState();
        log.debug("[{}] Tail reader starts after {} (head {}, {} running writers)", aggregateType, cursor, head, running.size());
    }

    /**
     * One read: the plain tail or the combined probe, then retirement and the hole read. Rows come back in
     * {@code global_order} order, late commits included; each row is returned once.
     */
    List<StoredEvent> poll() throws Exception {
        var combined = !candidates.isEmpty() || !lastReadContiguous || !holes.isEmpty() || !ranges.isEmpty();
        var rows     = new ArrayList<StoredEvent>();
        Set<String> running = null;
        var started  = System.nanoTime();
        seams.hooks().beforeRead(combined);
        if (!combined) {
            var statement = statement("plainTail", statements.plainTail());
            statement.setLong(1, cursor);
            statement.setInt(2, settings.tailPageSize());
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
            }
        } else {
            if (seams.has(RUNNING_SET_BEFORE_TAIL_READ)) {
                try (var resultSet = statement("runningSet", statements.runningSet()).executeQuery()) {
                    resultSet.next();
                    running = xids(resultSet.getArray(1));
                }
                seams.hooks().afterRunningSetRead();
            }
            var probed = probe(rows);
            if (running == null) {
                running = probed;
            }
            if (seams.has(EMPTY_TAIL_WITHOUT_RUNNING_SET) && rows.isEmpty()) {
                running = Set.of();
            }
        }
        lastReadNanos = System.nanoTime() - started;

        var published = new ArrayList<StoredEvent>(rows.size());
        var newHole   = false;
        for (var row : rows) {
            var g = row.globalOrder();
            if (g <= cursor) {
                if (removeHole(g)) {
                    published.add(row);
                }
            } else {
                newHole |= recordGap(cursor + 1, g - 1);
                cursor = g;
                published.add(row);
            }
        }
        lastReadContiguous = !newHole;

        if (running != null) {
            retire(running, published);
            var covered = Long.MIN_VALUE;
            for (var candidate : candidates) {
                covered = Math.max(covered, candidate.highestRead());
            }
            if (highestUnresolved() > covered) {
                candidates.add(new Candidate(cursor, running, false, System.nanoTime()));
            }
        }
        published.sort(Comparator.comparingLong(StoredEvent::globalOrder));
        publishState();
        observer.onRead(aggregateType, combined, lastReadNanos, rows.size(), holes.size() + ranges.size(), candidates.size(), oldestCandidateAge());
        return published;
    }

    private Set<String> probe(List<StoredEvent> rows) throws SQLException {
        PreparedStatement statement;
        if (seams.has(NO_HOLE_REREAD)) {
            statement = statement("probeWithoutHoleReread", statements.probeWithoutHoleReread());
            statement.setLong(1, cursor);
            statement.setInt(2, settings.tailPageSize());
        } else if (ranges.isEmpty()) {
            statement = statement("probe", statements.probe());
            statement.setLong(1, cursor);
            statement.setInt(2, settings.tailPageSize());
            statement.setArray(3, connection.createArrayOf("bigint", holes.toArray(Long[]::new)));
        } else {
            statement = statement("probeWithRanges", statements.probeWithRanges());
            statement.setLong(1, cursor);
            statement.setInt(2, settings.tailPageSize());
            statement.setArray(3, connection.createArrayOf("bigint", holes.toArray(Long[]::new)));
            statement.setArray(4, connection.createArrayOf("bigint", ranges.keySet().toArray(Long[]::new)));
            statement.setArray(5, connection.createArrayOf("bigint", ranges.values().toArray(Long[]::new)));
        }
        Set<String> running = null;
        try (var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                resultSet.getLong(1);
                if (resultSet.wasNull()) {
                    running = xids(resultSet.getArray(RUNNING_COLUMN));
                } else {
                    rows.add(mapper.map(resultSet));
                }
            }
        }
        if (running == null) {
            throw new IllegalStateException("The combined probe of " + aggregateType + " returned no running set");
        }
        return running;
    }

    private void retire(Set<String> running, List<StoredEvent> published) throws Exception {
        var maxRetired = Long.MIN_VALUE;
        for (var candidate : candidates) {
            if (candidate.retiresAgainst(running)) {
                maxRetired = Math.max(maxRetired, candidate.highestRead());
            }
        }
        if (maxRetired == Long.MIN_VALUE) {
            return;
        }
        // A later candidate's running set was read after an earlier candidate's highest read, so it covers that too
        final var retiredUpTo = maxRetired;
        candidates.removeIf(candidate -> candidate.highestRead() <= retiredUpTo);
        if (candidates.stream().noneMatch(Candidate::initial)) {
            initialized = true;
        }
        var resolved = new TreeSet<>(holes.headSet(retiredUpTo, true));
        holes.removeAll(resolved);
        pending.addAll(resolved);
        for (var it = ranges.entrySet().iterator(); it.hasNext(); ) {
            var range = it.next();
            if (range.getKey() > retiredUpTo) {
                break;
            }
            it.remove();
            pendingRanges.put(range.getKey(), Math.min(range.getValue(), retiredUpTo));
            if (range.getValue() > retiredUpTo) {
                ranges.put(retiredUpTo + 1, range.getValue());
                break;
            }
        }
        if (pending.isEmpty() && pendingRanges.isEmpty()) {
            return;
        }
        var values = new ArrayList<Long>(pending);
        seams.hooks().beforeHoleRead(values);
        var statement = statement("holeRead", statements.holeRead());
        statement.setArray(1, connection.createArrayOf("bigint", pending.toArray(Long[]::new)));
        statement.setArray(2, connection.createArrayOf("bigint", pendingRanges.keySet().toArray(Long[]::new)));
        statement.setArray(3, connection.createArrayOf("bigint", pendingRanges.values().toArray(Long[]::new)));
        var late = 0;
        try (var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                published.add(mapper.map(resultSet));
                late++;
            }
        }
        observer.onHoleRead(aggregateType, pending.size() + pendingRanges.size(), late);
        // Published: from now on the subscription holds them, and they are final
        pending.clear();
        pendingRanges.clear();
    }

    /**
     * @return {@code true} if the gap became a hole; gaps at or below {@link #finalUpTo} are final
     */
    private boolean recordGap(long from, long to) {
        from = Math.max(from, finalUpTo + 1);
        if (from > to) {
            return false;
        }
        if (to - from + 1 > settings.holeRangeThreshold()) {
            ranges.put(from, to);
        } else {
            for (var value = from; value <= to; value++) {
                holes.add(value);
            }
        }
        return true;
    }

    private boolean removeHole(long value) {
        if (holes.remove(value)) {
            return true;
        }
        var range = ranges.floorEntry(value);
        if (range == null || range.getValue() < value) {
            return false;
        }
        ranges.remove(range.getKey());
        if (range.getKey() < value) {
            ranges.put(range.getKey(), value - 1);
        }
        if (range.getValue() > value) {
            ranges.put(value + 1, range.getValue());
        }
        return true;
    }

    private long highestUnresolved() {
        var highest = Long.MIN_VALUE;
        if (!holes.isEmpty()) {
            highest = holes.last();
        }
        if (!ranges.isEmpty()) {
            highest = Math.max(highest, ranges.lastEntry().getValue());
        }
        return highest;
    }

    /**
     * @return the watermark (F2): every value at or below it is read or will never exist; {@code null} while the
     * restart's initial candidate is outstanding (F4)
     */
    Long watermark() {
        if (!initialized) {
            return seams.has(CATCH_UP_ABOVE_WATERMARK) ? cursor : null;
        }
        var low = seams.has(RESTARTED_READER_AT_HEAD) ? Math.max(cursor, finalUpTo) : cursor;
        var lowest = Long.MAX_VALUE;
        if (!holes.isEmpty()) {
            lowest = holes.first();
        }
        if (!ranges.isEmpty()) {
            lowest = Math.min(lowest, ranges.firstKey());
        }
        if (!seams.has(WATERMARK_BEFORE_HOLE_READ)) {
            if (!pending.isEmpty()) {
                lowest = Math.min(lowest, pending.first());
            }
            if (!pendingRanges.isEmpty()) {
                lowest = Math.min(lowest, pendingRanges.firstKey());
            }
        }
        return lowest == Long.MAX_VALUE ? low : Math.min(low, lowest - 1);
    }

    long cursor() {
        return cursor;
    }

    /**
     * @return whether the next {@link #poll()} should follow at once: the last read revealed a hole that has no
     * candidate yet, or filled a page
     */
    boolean readAgainNow(int lastRows) {
        return !lastReadContiguous && candidates.isEmpty() || lastRows >= settings.tailPageSize();
    }

    boolean hasOutstandingCandidates() {
        return !candidates.isEmpty();
    }

    long lastReadNanos() {
        return lastReadNanos;
    }

    /**
     * Copies of the state, published at the end of every read, so another thread (tests, diagnostics) can look
     */
    private volatile State state = new State(List.of(), new TreeSet<>(), new TreeMap<>(), 0, null);

    record State(List<Candidate> candidates, SortedSet<Long> holes, SortedMap<Long, Long> ranges, long cursor, Long watermark) {
    }

    private void publishState() {
        state = new State(List.copyOf(candidates), Collections.unmodifiableSortedSet(new TreeSet<>(holes)), Collections.unmodifiableSortedMap(new TreeMap<>(ranges)),
                          cursor, watermark());
    }

    /** The state after the last read; safe from any thread */
    State state() {
        return state;
    }

    private Duration oldestCandidateAge() {
        var oldest = candidates.stream().mapToLong(Candidate::createdNanos).min();
        return oldest.isPresent() ? Duration.ofNanos(System.nanoTime() - oldest.getAsLong()) : Duration.ZERO;
    }

    private PreparedStatement statement(String name, String sql) throws SQLException {
        var statement = prepared.get(name);
        if (statement == null) {
            statement = connection.prepareStatement(sql);
            // Server-prepared on first use, so the generic plan is used from the start (P0.16); a statement whose
            // generic plan is not index-only goes unnamed and is planned custom
            var generic = settings.genericPlans() && !CUSTOM_PLAN_STATEMENTS.contains(name);
            statement.unwrap(PGStatement.class).setPrepareThreshold(generic ? 1 : 0);
            prepared.put(name, statement);
        }
        return statement;
    }

    private static Set<String> xids(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        var values = (Object[]) array.getArray();
        var set    = new HashSet<String>(values.length * 2);
        for (var value : values) {
            set.add((String) value);
        }
        return set;
    }

    @Override
    public void close() {
        for (var statement : prepared.values()) {
            try {
                statement.close();
            } catch (SQLException e) {
                log.debug("[{}] Closing a statement failed", aggregateType, e);
            }
        }
        prepared.clear();
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                try (var statement = connection.createStatement()) {
                    statement.execute("RESET plan_cache_mode");
                    statement.execute("RESET jit");
                }
            }
        } catch (SQLException e) {
            log.debug("[{}] Resetting the tail reader's connection failed", aggregateType, e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("[{}] Closing the tail reader's connection failed", aggregateType, e);
        }
        connection = null;
    }

    /** The reader's connection, for tests that terminate its backend */
    Connection connection() {
        return connection;
    }
}
