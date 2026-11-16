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

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongConsumer;

import static dk.trustworks.essentials.components.eventlog.EventLogStorage.RUNNING_COLUMN;
import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The tail reader state machine of one aggregate type (plan §4, §6; design §3.3, phase 3 §1.1). Runs on the
 * {@link ReaderLoop}'s connection, which it is handed and does not own, and is touched by the loop's thread only:
 * cursor, hole set, candidates and watermark are plain fields (an assertion checks the owner thread under {@code -ea}).
 * A reader restart discards the instance and starts a new one.
 * <ul>
 *     <li><b>Cursor</b>: the highest {@code global_order} read. Every gap below it is a <b>hole</b> - a single value,
 *     or a range when a sequence jump left more than {@link EventLogSettings#holeRangeThreshold()} values.</li>
 *     <li>While a hole or candidate is outstanding, or the last read revealed a new hole, each read is the
 *     <b>combined probe</b>: tail above the cursor, outstanding holes re-read by {@code = ANY} and, while a range is
 *     outstanding, by a range join ({@code UNION ALL}, never {@code OR}: F1 with P0.11's form), and the running-writer
 *     set, read during execution after the statement's snapshot (§4.10, P0.2, P0.12) - also when no event row comes
 *     back. Otherwise the plain tail read. Since phase 6 the running set waits for the loop's probe clock: a read without
 *     it re-reads outstanding holes ({@code rereadTail}); several due types are read in one flush of such statements
 *     followed by one running set ({@link #execute(boolean)}, {@link #flushStatement()}, {@link #apply(Read, boolean)}).</li>
 *     <li>A probe creates a {@link Candidate} {@code (cursor, running set)} when a hole lies above every outstanding
 *     candidate, and retires each candidate whose set is disjoint from its running set. A retired candidate resolves
 *     every hole at or below it: those are re-read once ({@code holeRead}, §4.2) and the rows published before the
 *     watermark moves past them (F2).</li>
 *     <li><b>Watermark</b> (F2): {@code min(cursor, lowest unresolved hole - 1)}.</li>
 *     <li><b>Start</b> (F4, phase 3 §2.4): the reader starts at the head, read with the running set in one statement.
 *     If any writer is running, an <b>initial candidate</b> {@code (head, running set)} makes the watermark unknown
 *     until it retires, since a writer may still commit below the head. With no writer running the watermark is the
 *     head at once.</li>
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
    private final Connection                      connection;
    private final EventLogStorage.TailStatements  statements;
    private final EventLogSettings                settings;
    private final RowMapper                       mapper;
    private final EventLogObserver                observer;
    private final TestSeams                       seams;
    private final LongConsumer                    watermarkBeforeHoleRead;

    private Thread                                owner;
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
    private int                                   lastBackends = 1;

    /**
     * @param connection              the reader loop's connection; the loop sets its session and closes it
     * @param watermarkBeforeHoleRead {@link WatermarkMutant#WATERMARK_BEFORE_HOLE_READ} only: offers the watermark to
     *                                the attached inboxes before the hole read
     */
    TailReader(String aggregateType, Connection connection, EventLogStorage.TailStatements statements, EventLogSettings settings, RowMapper mapper,
               EventLogObserver observer, TestSeams seams, LongConsumer watermarkBeforeHoleRead) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.connection = requireNonNull(connection, "No connection provided");
        this.watermarkBeforeHoleRead = requireNonNull(watermarkBeforeHoleRead, "No watermarkBeforeHoleRead provided");
        this.statements = requireNonNull(statements, "No statements provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.mapper = requireNonNull(mapper, "No mapper provided");
        this.observer = requireNonNull(observer, "No observer provided");
        this.seams = requireNonNull(seams, "No seams provided");
    }

    /**
     * Start at the head (F4, phase 3 §2.4): one statement reads {@code max(global_order)} and the running set. Runs once,
     * on the loop's thread, which becomes the owner.
     */
    void startAtHead() throws SQLException {
        owner = Thread.currentThread();
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
            var set = RunningSet.of(resultSet.getArray(2));
            lastBackends = set.backends();
            running = set.xids();
        }
        cursor = head;
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
        log.debug("[{}] Tail reader starts at the head {} ({} running writers)", aggregateType, head, running.size());
    }

    /**
     * The raw result of one read, not yet applied to the reader (design phase 6 §4.2)
     *
     * @param rows     event rows in the order read, late commits included
     * @param running  the running set read after the read's snapshot, or {@code null} if none was read
     * @param nanos    what the statement (or the whole flush) took
     * @param combined whether a running set was read
     */
    record Read(List<StoredEvent> rows, RunningSet running, long nanos, boolean combined) {
    }

    /**
     * One running-set read ({@link EventLogStorage#RUNNING_SET})
     *
     * @param backends the number of backends the scan saw (cluster-wide)
     * @param xids     the running {@code backend_xid}s
     */
    record RunningSet(int backends, Set<String> xids) {
        static final RunningSet NONE = new RunningSet(1, Set.of());

        static RunningSet of(Array array) throws SQLException {
            if (array == null) {
                return NONE;
            }
            var values = (Object[]) array.getArray();
            if (values.length == 0) {
                return NONE;
            }
            var set = new HashSet<String>(values.length * 2);
            for (var i = 1; i < values.length; i++) {
                set.add((String) values[i]);
            }
            return new RunningSet(Integer.parseInt((String) values[0]), set);
        }
    }

    /**
     * @return whether the next read should read the running set: a candidate or hole is outstanding, or the last read
     * revealed a hole (the phase-2 condition for the combined probe)
     */
    boolean wantsRunningSet() {
        return !candidates.isEmpty() || !lastReadContiguous || !holes.isEmpty() || !ranges.isEmpty();
    }

    /**
     * One read of this type alone, then {@link #apply}. See {@link #execute(boolean)}.
     */
    List<StoredEvent> poll() throws Exception {
        return apply(execute(true), false);
    }

    /**
     * One read of this type alone (design phase 6 §9 decision 2): the combined probe if a running set is wanted and
     * {@code runningSetAllowed} (the probe clock, §5), else the tail - with the hole re-read while holes are outstanding
     * ({@code rereadTail}), so a late commit is read by every read whatever the probe does
     */
    Read execute(boolean runningSetAllowed) throws Exception {
        assert ownedByCurrentThread();
        var combined = runningSetAllowed && wantsRunningSet();
        var rows     = new ArrayList<StoredEvent>();
        RunningSet running = null;
        var started  = System.nanoTime();
        seams.hooks().beforeRead(combined);
        if (!combined) {
            var statement = rereads() ? statement(ranges.isEmpty() ? "rereadTail" : "rereadTailWithRanges", flushStatement())
                                      : statement("plainTail", statements.plainTail());
            bindFlushStatement(statement, 1);
            try (var resultSet = statement.executeQuery()) {
                readRows(resultSet, rows);
            }
        } else {
            if (seams.has(RUNNING_SET_BEFORE_TAIL_READ)) {
                try (var resultSet = statement("runningSet", statements.runningSet()).executeQuery()) {
                    resultSet.next();
                    running = RunningSet.of(resultSet.getArray(1));
                }
                seams.hooks().afterRunningSetRead();
            }
            var probed = probe(rows);
            if (running == null) {
                running = probed;
            }
        }
        return new Read(rows, running, System.nanoTime() - started, combined);
    }

    private boolean rereads() {
        return !seams.has(NO_HOLE_REREAD) && (!holes.isEmpty() || !ranges.isEmpty());
    }

    /**
     * @return this type's statement in a flush of several types (design phase 6 §4.2): the plain tail, or the tail with
     * the hole re-read while holes are outstanding; never a running-set branch
     */
    String flushStatement() {
        if (!rereads()) {
            return statements.plainTail();
        }
        return ranges.isEmpty() ? statements.rereadTail() : statements.rereadTailWithRanges();
    }

    /**
     * Bind {@link #flushStatement()}'s parameters from {@code index} on
     *
     * @return the index of the next statement's first parameter
     */
    int bindFlushStatement(PreparedStatement statement, int index) throws SQLException {
        assert ownedByCurrentThread();
        var rereads = rereads();
        statement.setLong(index++, cursor);
        statement.setInt(index++, settings.tailPageSize());
        if (rereads) {
            statement.setArray(index++, connection.createArrayOf("bigint", holes.toArray(Long[]::new)));
            if (!ranges.isEmpty()) {
                statement.setArray(index++, connection.createArrayOf("bigint", ranges.keySet().toArray(Long[]::new)));
                statement.setArray(index++, connection.createArrayOf("bigint", ranges.values().toArray(Long[]::new)));
            }
        }
        return index;
    }

    /** Map every row of a {@link #flushStatement()} result */
    void readRows(ResultSet resultSet, List<StoredEvent> rows) throws SQLException {
        while (resultSet.next()) {
            rows.add(mapper.map(resultSet));
        }
    }

    /** Reader loop: the hook before a read of this type inside a flush */
    void beforeFlushRead(boolean combined) throws Exception {
        seams.hooks().beforeRead(combined);
    }

    /**
     * Apply one read: move the cursor, resolve re-read holes, record new holes, then - if a running set was read -
     * retire candidates (with the hole read) and create one. Rows come back in {@code global_order} order, late commits
     * included; each row is returned once.
     *
     * @param woken whether the read followed a wake ({@link WatermarkMutant#WAKE_ADVANCES_WATERMARK} only)
     */
    List<StoredEvent> apply(Read read, boolean woken) throws Exception {
        assert ownedByCurrentThread();
        var rows    = read.rows();
        Set<String> running = null;
        if (read.running() != null) {
            lastBackends = read.running().backends();
            running = read.running().xids();
            if (seams.has(EMPTY_TAIL_WITHOUT_RUNNING_SET) && rows.isEmpty()) {
                running = Set.of();
            }
        }
        lastReadNanos = read.nanos();

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
        if (woken && rows.isEmpty() && seams.has(WAKE_ADVANCES_WATERMARK)) {
            holes.clear();
            ranges.clear();
            candidates.clear();
            initialized = true;
            finalUpTo = Math.max(finalUpTo, cursor);
        }

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
        observer.onRead(aggregateType, read.combined(), read.nanos(), rows.size(), holes.size() + ranges.size(), candidates.size(), oldestCandidateAge());
        return published;
    }

    private RunningSet probe(List<StoredEvent> rows) throws SQLException {
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
        RunningSet running = null;
        try (var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                resultSet.getLong(1);
                if (resultSet.wasNull()) {
                    running = RunningSet.of(resultSet.getArray(RUNNING_COLUMN));
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
        if (seams.has(WATERMARK_BEFORE_HOLE_READ)) {
            publishState();
            var early = watermark();
            if (early != null) {
                watermarkBeforeHoleRead.accept(early);
            }
        }
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
     * A {@code FRESH} subscription attached under the unknown watermark hands off the gaps its bridge found (design
     * phase 5 §6.3): while the restart's initial candidate is outstanding, every gap at or below its head becomes an
     * unresolved hole of that candidate - re-read by every tail statement (F1) and resolved only by the hole read after
     * it retires (F2). Gaps above the head are already holes of this reader, or final. Holes and ranges stay disjoint,
     * since several subscriptions may hand off overlapping gaps and a value re-read twice would be published twice.
     *
     * @return {@code false} if the initial candidate has retired: every gap at or below the head is final, and nothing
     * is held
     */
    boolean handOff(List<HandledSet.Gap> gaps) {
        assert ownedByCurrentThread();
        var initial = candidates.stream().filter(Candidate::initial).findFirst();
        if (initial.isEmpty()) {
            return false;
        }
        if (seams.has(HANDED_GAPS_RESOLVED_AT_ONCE)) {
            return true;
        }
        var head = initial.get().highestRead();
        for (var gap : gaps) {
            var high = Math.min(gap.high(), head);
            if (gap.low() > high) {
                continue;
            }
            if (high - gap.low() + 1 > settings.holeRangeThreshold()) {
                addUnresolvedRange(gap.low(), high);
            } else {
                for (var value = gap.low(); value <= high; value++) {
                    var range = ranges.floorEntry(value);
                    if (range == null || range.getValue() < value) {
                        holes.add(value);
                    }
                }
            }
        }
        publishState();
        return true;
    }

    private void addUnresolvedRange(long low, long high) {
        var below = ranges.floorEntry(low);
        if (below != null && below.getValue() >= low - 1) {
            low = below.getKey();
            high = Math.max(high, below.getValue());
            ranges.remove(below.getKey());
        }
        for (var it = ranges.subMap(low, true, high + 1, true).entrySet().iterator(); it.hasNext(); ) {
            high = Math.max(high, it.next().getValue());
            it.remove();
        }
        holes.subSet(low, true, high, true).clear();
        ranges.put(low, high);
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
        assert ownedByCurrentThread();
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
        assert ownedByCurrentThread();
        return cursor;
    }

    /** The reply to an attach at the loop's quiescent point: the unresolved holes and ranges, all above the watermark */
    SortedSet<Long> holes() {
        assert ownedByCurrentThread();
        return Collections.unmodifiableSortedSet(new TreeSet<>(holes));
    }

    SortedMap<Long, Long> ranges() {
        assert ownedByCurrentThread();
        return Collections.unmodifiableSortedMap(new TreeMap<>(ranges));
    }

    private boolean ownedByCurrentThread() {
        if (owner != null && owner != Thread.currentThread()) {
            throw new AssertionError("Tail reader of " + aggregateType + " touched by " + Thread.currentThread() + "; it belongs to " + owner);
        }
        return true;
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
     * @return the number of backends the last running-set read saw (cluster-wide, every database)
     */
    int lastBackends() {
        return lastBackends;
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

    /** Close the prepared statements; the connection is the loop's */
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
    }
}
