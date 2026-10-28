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

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ordered unit released without waiting for its handlers - on request ({@code ShardOwnedQueue.releaseKeptOrderedUnit}),
 * or by a shed past {@code stuckHandlerTimeout} - names the keys still in a handler, and says their messages are delivered
 * again: exactly the keys whose acknowledgements it did not flush. A handler queues its acknowledgement before its key
 * leaves its handler bookkeeping, so the keys are read together with the batch the flush takes
 * ({@link OrderedShardOwner#flushAcksNamingKeysInHandler}), and nothing flushes the owner after that flush.
 * <p>
 * Here a handler returns while that flush's write runs, after the batch was taken. The release on request read the keys
 * after its flush: that handler went unnamed though its acknowledgement was never flushed, and its message was delivered
 * again after the answer and the log said it would not be. The shed read them before its flush, and the pump flushed again
 * in the same pass: that handler was named though its acknowledgement had been flushed.
 */
class OrderedShardOwnerKeysNamedAtReleaseTest {

    private static final Duration SHED_GRACE            = Duration.ofMillis(1);
    private static final Duration STUCK_HANDLER_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void a_handler_returning_while_the_flush_of_a_release_on_request_runs_is_named_and_its_acknowledgement_is_not_flushed() throws Exception {
        var owner = owner();
        enterHandler(owner, "HUNG", 1L, Duration.ZERO);
        enterHandler(owner, "RETURNS-DURING-THE-FLUSH", 2L, Duration.ZERO);
        enterHandler(owner, "RETURNED-BEFORE", 3L, Duration.ZERO);
        returnFromHandler(owner, "RETURNED-BEFORE", 3L);
        var database = new AcknowledgingConnection(() -> returnFromHandler(owner, "RETURNS-DURING-THE-FLUSH", 2L));

        var named = owner.flushAcksNamingKeysInHandler(database.connection());

        assertThat(database.writes).as("premise: the flush wrote once, and the handler returned while it ran").isEqualTo(1);
        assertThat(database.acknowledged).as("the flush acknowledges what had returned before it took its batch").containsExactly(3L);
        assertThat(named).as("every key whose acknowledgement was not flushed is named")
                         .hasValueSatisfying(keys -> assertThat(keys.keys()).containsExactlyInAnyOrder("HUNG", "RETURNS-DURING-THE-FLUSH"));
        assertThat(pendingAcks(owner)).as("the acknowledgement queued while the flush ran is left unflushed").containsExactly(2L);
    }

    @Test
    void a_handler_returning_while_a_shed_past_the_stuck_handler_timeout_flushes_is_named_and_its_acknowledgement_is_not_flushed() throws Exception {
        var owner = owner();
        enterHandler(owner, "STUCK", 1L, STUCK_HANDLER_TIMEOUT.plusSeconds(1));
        enterHandler(owner, "RETURNS-DURING-THE-FLUSH", 2L, Duration.ZERO);
        enterHandler(owner, "RETURNED-BEFORE", 3L, Duration.ZERO);
        returnFromHandler(owner, "RETURNED-BEFORE", 3L);
        var database = new AcknowledgingConnection(() -> returnFromHandler(owner, "RETURNS-DURING-THE-FLUSH", 2L));
        assertThat(owner.beginShedding()).as("premise: the shed starts").isTrue();
        sleepAtLeast(System.nanoTime(), SHED_GRACE.multipliedBy(5));
        // An empty batched read, so the pass reads nothing from the database.
        owner.applyBatchRead(List.of(), 500, null, OptionalLong.empty());

        owner.pumpOnce(database.connection());

        assertThat(owner.shedComplete()).as("premise: the shed completed past the stuck handler").isTrue();
        assertThat(database.writes).as("premise: the handler returned while the shed's flush ran").isPositive();
        assertThat(owner.shedPastStuckHandler()).as("the keys the release WARNs are delivered again")
                                                .hasValueSatisfying(keys -> assertThat(keys.keys()).containsExactlyInAnyOrder("STUCK", "RETURNS-DURING-THE-FLUSH"));
        assertThat(database.acknowledged).as("no key named had its acknowledgement flushed").containsExactly(3L);

        owner.pumpOnce(database.connection());

        assertThat(database.acknowledged).as("nor on a later pass, before the queue releases the unit").containsExactly(3L);
        assertThat(pendingAcks(owner)).as("the acknowledgement queued while the flush ran is left unflushed").containsExactly(2L);
    }

    private static OrderedShardOwner owner() {
        var settings = ShardOwnerSettings.builder().setShedGrace(SHED_GRACE).setStuckHandlerTimeout(STUCK_HANDLER_TIMEOUT).build();
        // Nothing here reaches the DataSource: every write goes through the connection each test passes.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        return new OrderedShardOwner(new ShardOwnedStorage(noDatabase, (short) 1), 3, 1L, settings, (id, key, payload, type) -> { },
                                     new ShardOwnerMetrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-under-test",
                                     new ShardWakeup(), HandlerDispatch.inline(), false);
    }

    /**
     * Put {@code key} in a handler with message {@code seq}, there for {@code inHandlerFor}, as dispatch does. Set directly:
     * reaching it through dispatch would need a database read.
     */
    private static void enterHandler(OrderedShardOwner owner, String key, long seq, Duration inHandlerFor) throws Exception {
        synchronized (stateLock(owner)) {
            keysInFlight(owner).put(key, new OrderedShardOwner.InHandler(seq, seq, System.nanoTime() - inHandlerFor.toNanos()));
        }
    }

    /**
     * Return {@code key}'s handler with message {@code seq} handled, in the two steps {@code OrderedShardOwner.runHandler}
     * takes: the acknowledgement queued, then the key out of its handler bookkeeping, each in its own hold of the lock.
     */
    private static void returnFromHandler(OrderedShardOwner owner, String key, long seq) {
        try {
            synchronized (stateLock(owner)) {
                pendingAcks(owner).add(seq);
            }
            synchronized (stateLock(owner)) {
                keysInFlight(owner).remove(key);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OrderedShardOwner.InHandler> keysInFlight(OrderedShardOwner owner) throws ReflectiveOperationException {
        return (Map<String, OrderedShardOwner.InHandler>) field(owner, "keysInFlight");
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> pendingAcks(OrderedShardOwner owner) throws ReflectiveOperationException {
        return (Set<Long>) field(owner, "pendingAcks");
    }

    private static Object stateLock(OrderedShardOwner owner) throws ReflectiveOperationException {
        return field(owner, "stateLock");
    }

    private static Object field(OrderedShardOwner owner, String name) throws ReflectiveOperationException {
        var field = OrderedShardOwner.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void sleepAtLeast(long sinceNanos, Duration duration) {
        while (System.nanoTime() - sinceNanos < duration.toNanos()) {
            LockSupport.parkNanos(duration.toNanos());
        }
    }

    /**
     * A connection that answers the ordered acknowledgement's {@code DELETE} as if every row it names were deleted,
     * recording the sequence values, and runs {@code duringFirstWrite} while the first one runs - after the flush took its
     * batch. Any other statement fails the test.
     */
    private static final class AcknowledgingConnection {
        private final List<Long> acknowledged = new ArrayList<>();
        private final Runnable   duringFirstWrite;
        private       List<Long> lastBatch    = List.of();
        private       int        writes;

        AcknowledgingConnection(Runnable duringFirstWrite) {
            this.duringFirstWrite = duringFirstWrite;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement" -> {
                        var sql = (String) args[0];
                        if (!sql.startsWith("DELETE FROM " + ShardOwnedSchema.ORDERED_TABLE)) {
                            throw new UnsupportedOperationException(sql);
                        }
                        return statement();
                    }
                    case "createArrayOf" -> {
                        lastBatch = Arrays.stream((Object[]) args[1]).map(Long.class::cast).toList();
                        return null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                                              (proxy, method, args) -> {
                                                                  var name = method.getName();
                                                                  if ("executeUpdate".equals(name)) {
                                                                      var firstWrite = writes == 0;
                                                                      writes++;
                                                                      if (firstWrite) {
                                                                          duringFirstWrite.run();
                                                                      }
                                                                      acknowledged.addAll(lastBatch);
                                                                      return lastBatch.size();
                                                                  }
                                                                  if ("close".equals(name) || name.startsWith("set")) {
                                                                      return null;
                                                                  }
                                                                  if ("isWrapperFor".equals(name)) {
                                                                      // Not pgjdbc's: ShardOwnedStorage.prepareFreshlyPlanned leaves its plan cache alone
                                                                      return false;
                                                                  }
                                                                  throw new UnsupportedOperationException(name);
                                                              });
        }
    }
}
