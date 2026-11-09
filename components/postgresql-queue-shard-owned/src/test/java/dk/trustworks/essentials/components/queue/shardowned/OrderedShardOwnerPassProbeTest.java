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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pump's horizon probe for a pass ({@link OrderedShardOwner#applyHorizonProbe}) stands in for the owner's own only when
 * it was issued after the rows the pass takes in were read. An owner with no rows read for the pass - no batched read and
 * no read-ahead - reads inside {@link OrderedShardOwner#pumpOnce}, after the probe, so it must take a probe of its own. That
 * used to be decided by comparing the probe's {@link System#nanoTime()} stamp against {@code Long.MAX_VALUE}, which
 * overflowed and kept the probe whenever the stamp was negative - and {@code nanoTime} has an arbitrary origin.
 */
class OrderedShardOwnerPassProbeTest {

    private static final String HORIZON_PROBE = "pg_stat_activity";

    @Test
    void a_pass_probe_stamped_with_a_negative_nano_time_is_not_used_when_no_rows_were_read_before_it() throws Exception {
        var owner = behindItsWatermark();
        var database = new RecordingConnection();

        owner.applyHorizonProbe(Set.of(), -1_000_000_000L);
        owner.pumpOnce(database.connection());

        assertThat(database.statements).as("premise: the owner read its rows itself, after the probe")
                                       .anyMatch(sql -> sql.contains("seq > ?"));
        assertThat(database.statements).as("the owner took its own probe after that read").filteredOn(sql -> sql.contains(HORIZON_PROBE)).hasSize(1);
    }

    @Test
    void a_pass_probe_issued_after_the_read_ahead_is_used() throws Exception {
        var owner = behindItsWatermark();
        var database = new RecordingConnection();
        owner.readAhead(database.connection());

        // A stamp a millisecond after the read-ahead's, so the probe is after the read whatever the clock's resolution
        owner.applyHorizonProbe(Set.of(), getLong(owner, "readAheadByNanos") + 1_000_000L);
        owner.pumpOnce(database.connection());

        assertThat(database.statements).as("premise: the read-ahead read the rows").filteredOn(sql -> sql.contains("seq > ?")).hasSize(1);
        assertThat(database.statements).as("the pump's probe stood in for the owner's own").noneMatch(sql -> sql.contains(HORIZON_PROBE));
    }

    /**
     * An owner that has seen a value above its watermark, so its pass asks for a horizon probe, and whose head sweep is not
     * due, so the pass issues only the cursor read and the probe.
     */
    private static OrderedShardOwner behindItsWatermark() throws Exception {
        var settings = ShardOwnerSettings.builder().build();
        // Nothing here reaches the DataSource: every statement goes through the connection each test passes.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        var owner = new OrderedShardOwner(new ShardOwnedStorage(noDatabase, (short) 1), 3, 1L, settings, (id, key, payload, type) -> { },
                                          new ShardOwnerMetrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-under-test",
                                          new ShardWakeup(), HandlerDispatch.inline(), false);
        setLong(owner, "maxSeen", 10L);
        setLong(owner, "lastSweepNanos", System.nanoTime());
        return owner;
    }

    private static void setLong(OrderedShardOwner owner, String name, long value) throws ReflectiveOperationException {
        var field = OrderedShardOwner.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(owner, value);
    }

    private static long getLong(OrderedShardOwner owner, String name) throws ReflectiveOperationException {
        var field = OrderedShardOwner.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(owner);
    }

    /**
     * A connection answering every statement with no rows, recording the SQL of each.
     */
    private static final class RecordingConnection {
        final List<String> statements = new CopyOnWriteArrayList<>();

        // Connection's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
        // its interface, which the context loader need not.
        @SuppressWarnings("PMD.UseProperClassLoader")
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    statements.add((String) args[0]);
                    return statement();
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        // PreparedStatement's own loader, not the thread's context loader: a proxy class must be defined in a loader that
        // sees its interface, which the context loader need not.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private static PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                                              (proxy, method, args) -> switch (method.getName()) {
                                                                  case "executeQuery" -> noRows();
                                                                  case "isWrapperFor" -> false;
                                                                  case "close" -> null;
                                                                  default -> {
                                                                      if (method.getName().startsWith("set")) {
                                                                          yield null;
                                                                      }
                                                                      throw new UnsupportedOperationException(method.getName());
                                                                  }
                                                              });
        }

        // ResultSet's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees its
        // interface, which the context loader need not.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private static ResultSet noRows() {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                                                      (proxy, method, args) -> switch (method.getName()) {
                                                          case "next" -> false;
                                                          case "close" -> null;
                                                          default -> throw new UnsupportedOperationException(method.getName());
                                                      });
        }
    }
}
