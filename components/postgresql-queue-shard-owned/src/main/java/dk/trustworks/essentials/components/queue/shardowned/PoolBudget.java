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

package dk.trustworks.essentials.components.queue.shardowned;

import org.slf4j.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Refuses a connection pool the {@link ShardRuntime} would exhaust, before it takes anything from it.
 * <p>
 * <b>Why this exists.</b> Every pump holds one connection for as long as the runtime runs, and so does
 * the wake-up listener: {@code pumpThreads + 1} connections that never go back to the pool. Nothing
 * about that fails when the pool is too small. A pump that cannot get a connection hits the pool's
 * timeout, logs and retries, forever, while the shards it serves stop delivering — it reads as a
 * database blip, not as configuration. And a pool that fits the held connections exactly is no
 * better: lease renewal, enqueue and acknowledgement sessions draw from the same pool transiently, so
 * with nothing left over the leases lapse and every shard stops. The Spring starter shares the
 * application's own {@code DataSource}, which makes the rest of the application the next thing
 * starved.
 * <p>
 * So this checks the arithmetic up front, from the pool's {@link ConnectionPoolMetadata#maximumSize()}:
 * <ul>
 *   <li>a pool no larger than the held connections is refused — nothing would be left for lease
 *       renewal, so the engine could not work at all;</li>
 *   <li>a pool more than half consumed by held connections is accepted with a warning, because what
 *       remains is shared with the rest of the application and how much that needs is not knowable
 *       here.</li>
 * </ul>
 * Otherwise the numbers are logged at INFO, whether the size is known or not, so the share of the
 * pool the engine takes is visible on every start-up rather than only once it is a problem.
 * <p>
 * <b>Several runtimes on one {@code DataSource}.</b> The shared runtime is one per {@code DataSource},
 * but a caller can construct runtimes of their own on it. So the held connections of every running
 * runtime are also added up per {@code DataSource}, by identity, and the total is held to the same
 * thresholds — but only ever as a warning. The refusal stays a judgement about one runtime alone,
 * because the sum depends on bookkeeping across start and stop, and a bookkeeping slip must cost at
 * most a spurious log line, never a refused start on a healthy pool. Two different {@code DataSource}
 * wrappers around one pool are counted separately; nothing here can tell they share a pool.
 * <p>
 * <b>Exhaustion at runtime.</b> A pump that later fails to acquire a connection — reconnecting after a
 * blip while the application has the pool — asks {@link #exhaustion} whether the pool is genuinely
 * full, so it can say that instead of reporting a lost connection. A pool's acquisition timeout is
 * the same exception whether the pool is full or the database is down; the pool's live count of
 * checked-out connections is not.
 */
final class PoolBudget {
    private static final Logger log = LoggerFactory.getLogger(PoolBudget.class);

    /**
     * Held connections of every running runtime, per {@code DataSource}. Keyed by identity for the
     * reason {@code ShardRuntime.SHARED} is: a {@code DataSource} is a resource, not a value.
     */
    private static final Map<DataSource, Integer> HELD = new IdentityHashMap<>();

    /**
     * What a running runtime has counted against its {@code DataSource}, handed back to
     * {@link #release} when it stops.
     */
    record Reservation(DataSource dataSource, int held) {
    }

    private PoolBudget() {
    }

    /**
     * The connections a runtime with this many pumps holds for its whole lifetime: one per pump, plus
     * the wake-up listener.
     */
    static int heldConnections(int pumpThreads) {
        return Math.max(1, pumpThreads) + 1;
    }

    /**
     * Throw if the pool cannot sustain a runtime with {@code pumpThreads} pumps; warn if it can but
     * leaves little over for everything else that shares it. Otherwise, count this runtime against
     * {@code dataSource} until {@link #release}.
     *
     * @throws IllegalStateException if the pool's maximum size does not exceed this runtime's held
     *                               connections
     */
    static Reservation reserve(DataSource dataSource, ConnectionPoolMetadata pool, int pumpThreads) {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(pool, "No pool metadata provided");
        var held    = heldConnections(pumpThreads);
        var maximum = maximumSize(pool);
        if (maximum.isPresent() && maximum.getAsInt() <= held) {
            throw new IllegalStateException(
                    "The connection pool allows at most " + maximum.getAsInt() + " connections, and the "
                            + "shard-owned queue runtime holds " + held + " of them permanently ("
                            + (held - 1) + " pumps + 1 listener). Nothing would be left for lease renewal, "
                            + "enqueue or acknowledgement: pumps would retry forever without a connection "
                            + "and leases would lapse. Raise the pool's maximum size well above " + held
                            + ", or lower pumpThreads.");
        }
        int total;
        synchronized (HELD) {
            total = held + HELD.getOrDefault(dataSource, 0);
            HELD.put(dataSource, total);
        }
        var assessment = assess(held, total, maximum);
        if (assessment.warn()) {
            log.warn(assessment.message());
        } else {
            log.info(assessment.message());
        }
        return new Reservation(dataSource, held);
    }

    /**
     * What to tell the operator about a pool once a runtime has been counted against it.
     *
     * @param warn    whether it deserves a WARN rather than an INFO
     * @param message the line to log
     */
    record Assessment(boolean warn, String message) {
    }

    /**
     * The start-up line about the pool, logged every time rather than only when something is wrong.
     * <p>
     * It used to say nothing while held connections stayed at or below half the pool, which is the
     * common case and also the one where nobody learns the number. The number is the point: an
     * application on a default pool of 10 gives up three connections to a single runtime, and six to
     * two — and two runtimes on one {@code DataSource} was a real misconfiguration that stayed
     * invisible, because only the half-pool threshold could make it speak.
     *
     * @param held    connections this runtime holds permanently
     * @param total   connections held by every running runtime on the same {@code DataSource},
     *                this one included
     * @param maximum the pool's maximum size, if known
     */
    static Assessment assess(int held, int total, OptionalInt maximum) {
        var others = total - held;
        var detail = (held - 1) + " pump(s) + 1 listener"
                + (others > 0 ? ", plus " + others + " held by other shard-owned queue runtimes on this DataSource" : "");
        if (maximum.isEmpty()) {
            return new Assessment(false,
                    "Shard-owned queue: holding " + total + " connection(s) from this DataSource permanently (" + detail
                            + "). The pool's maximum size is not known, so it is not checked: size the pool for "
                            + "these plus lease renewal, enqueue and the rest of the application - at least "
                            + (total * 2) + " to keep them under half of it.");
        }
        var size    = maximum.getAsInt();
        var percent = (int) Math.round(total * 100.0d / size);
        var left    = Math.max(0, size - total);
        var holding = "Shard-owned queue: holding " + total + " of the connection pool's " + size
                + " connections permanently (" + percent + "%; " + detail + ")";
        if (total >= size) {
            return new Assessment(true,
                    holding + ", leaving nothing for lease renewal, enqueue or the rest of the application. "
                            + advice(total, others));
        }
        if (total * 2 > size) {
            return new Assessment(true,
                    holding + ", leaving " + left + " for lease renewal, enqueue, acknowledgement and the rest "
                            + "of the application. " + advice(total, others));
        }
        return new Assessment(false, holding + ", leaving " + left + " for the rest of the application.");
    }

    private static String advice(int total, int others) {
        return "Raise the pool's maximum size to at least " + (total * 2)
                + " (Spring Boot with HikariCP: spring.datasource.hikari.maximum-pool-size; its default is 10)"
                + (others > 0 ? ", share one ShardRuntime across the queues," : "")
                + " or lower pumpThreads.";
    }

    /**
     * Stop counting a stopped runtime against its {@code DataSource}.
     */
    static void release(Reservation reservation) {
        requireNonNull(reservation, "No reservation provided");
        synchronized (HELD) {
            var remaining = HELD.getOrDefault(reservation.dataSource(), 0) - reservation.held();
            if (remaining > 0) {
                HELD.put(reservation.dataSource(), remaining);
            } else {
                HELD.remove(reservation.dataSource());
            }
        }
    }

    /**
     * Held connections currently counted against {@code dataSource}, across every running runtime.
     */
    static int heldAgainst(DataSource dataSource) {
        synchronized (HELD) {
            return HELD.getOrDefault(dataSource, 0);
        }
    }

    /**
     * A description of the pool being exhausted — every connection checked out — or empty if it is
     * not, or cannot be told. Read at the moment of a failed acquisition, so it separates a full pool
     * from an unreachable database.
     */
    static Optional<String> exhaustion(DataSource dataSource, ConnectionPoolMetadata pool) {
        var maximum = maximumSize(pool);
        var active  = ask(pool::activeConnections);
        if (maximum.isEmpty() || active.isEmpty() || active.getAsInt() < maximum.getAsInt()) {
            return Optional.empty();
        }
        return Optional.of("connection pool exhausted: " + active.getAsInt() + " of " + maximum.getAsInt()
                                   + " connections checked out, " + heldAgainst(dataSource)
                                   + " of them held permanently by shard-owned queue runtimes");
    }

    /**
     * The pool's maximum size, where it is known and bounded.
     */
    private static OptionalInt maximumSize(ConnectionPoolMetadata pool) {
        var maximum = ask(pool::maximumSize);
        // Some pools report "unbounded" as zero or a negative number; treat it as not known.
        return maximum.isPresent() && maximum.getAsInt() > 0 ? maximum : OptionalInt.empty();
    }

    /**
     * Metadata is supplied from outside the engine and is only ever advice, so a failing or null answer
     * is treated as no answer rather than as a reason to refuse or to stop.
     */
    private static OptionalInt ask(Supplier<OptionalInt> question) {
        try {
            var answer = question.get();
            return answer == null ? OptionalInt.empty() : answer;
        } catch (RuntimeException e) {
            log.debug("Connection pool metadata could not be read", e);
            return OptionalInt.empty();
        }
    }
}
