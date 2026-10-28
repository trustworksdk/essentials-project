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

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A connection for an owner's charge before a release ({@code ShardOwner.chargeInHandlerBeforeRelease},
 * {@code OrderedShardOwner.chargeKeysInHandlerBeforeRelease}), driven without a database: it answers the charge's
 * {@code UPDATE ... RETURNING} on {@code table} with {@code chargedAttempts} - the attempt count after the charge of each row
 * named - and the dead-letter move's {@code INSERT} and {@code DELETE} as if each moved the row, recording its sequence
 * value; the move's {@code INSERT} of a row in {@code rowsGone} inserts nothing, as for a row no longer in the table or no
 * longer the owner's. Any other statement fails the test. {@link #onCharge} runs while the charge's {@code UPDATE} runs.
 */
final class ChargingConnection {
    private final String             table;
    private final Map<Long, Integer> chargedAttempts;
    private final Set<Long>          rowsGone;
    /**
     * The sequence value of each row the dead-letter move moved, in the order moved.
     */
    final         List<Long>         moved         = new CopyOnWriteArrayList<>();
    /**
     * The sequence values each charge asked for, one set per charge.
     */
    final         List<Set<Long>>    chargesAsked  = new CopyOnWriteArrayList<>();
    private volatile Runnable        onCharge      = () -> { };

    ChargingConnection(String table, Map<Long, Integer> chargedAttempts) {
        this(table, chargedAttempts, Set.of());
    }

    ChargingConnection(String table, Map<Long, Integer> chargedAttempts, Set<Long> rowsGone) {
        this.table = table;
        this.chargedAttempts = chargedAttempts;
        this.rowsGone = rowsGone;
    }

    /**
     * Run {@code action} on the charging thread while the charge's {@code UPDATE} runs: the charge has started, and what it
     * asks for is decided.
     */
    ChargingConnection onCharge(Runnable action) {
        this.onCharge = action;
        return this;
    }

    // Connection's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement" -> {
                    var sql = (String) args[0];
                    if (sql.startsWith("UPDATE " + table)) {
                        return statement(sql, true);
                    }
                    if (sql.startsWith("INSERT INTO ") || sql.startsWith("DELETE FROM " + table)) {
                        return statement(sql, false);
                    }
                    throw new UnsupportedOperationException(sql);
                }
                case "createArrayOf" -> {
                    return array((Object[]) args[1]);
                }
                case "setAutoCommit", "commit", "rollback" -> {
                    return null;
                }
                case "getAutoCommit" -> {
                    return true;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    // Array's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees its
    // interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static Array array(Object... elements) {
        return (Array) Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[]{Array.class}, (proxy, method, args) -> {
            if ("getArray".equals(method.getName())) {
                return elements;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    // PreparedStatement's own loader, not the thread's context loader: a proxy class must be defined in a loader that
    // sees its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private PreparedStatement statement(String sql, boolean charge) {
        var seq   = new long[1];
        var asked = new TreeSet<Long>();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                                          (proxy, method, args) -> {
                                                              var name = method.getName();
                                                              // Parameter 5 of the move's INSERT is the row's seq (m.seq = ? in
                                                              // ShardOwnedStorage.moveToDeadLetter): this depends on that bind order.
                                                              if ("setLong".equals(name) && !charge && (int) args[0] == 5) {
                                                                  seq[0] = (long) args[1];
                                                              }
                                                              // Parameter 3 of the charge's UPDATE is the array of the seqs asked for
                                                              // (o.seq = ANY(?) in ShardOwnedStorage.chargeRowsInHandler)
                                                              if ("setArray".equals(name) && charge && (int) args[0] == 3) {
                                                                  for (var element : (Object[]) ((Array) args[1]).getArray()) {
                                                                      asked.add((Long) element);
                                                                  }
                                                              }
                                                              if ("executeQuery".equals(name) && charge) {
                                                                  chargesAsked.add(Set.copyOf(asked));
                                                                  onCharge.run();
                                                                  var rows = new ArrayList<Map.Entry<Long, Integer>>();
                                                                  new TreeMap<>(chargedAttempts).forEach((chargedSeq, attempts) -> {
                                                                      if (asked.contains(chargedSeq)) {
                                                                          rows.add(Map.entry(chargedSeq, attempts));
                                                                      }
                                                                  });
                                                                  return resultSet(rows);
                                                              }
                                                              if ("executeUpdate".equals(name) && !charge) {
                                                                  if (sql.startsWith("INSERT INTO ")) {
                                                                      if (rowsGone.contains(seq[0])) {
                                                                          return 0;
                                                                      }
                                                                      moved.add(seq[0]);
                                                                  }
                                                                  return 1;
                                                              }
                                                              if ("close".equals(name) || name.startsWith("set")) {
                                                                  return null;
                                                              }
                                                              throw new UnsupportedOperationException(name);
                                                          });
    }

    // ResultSet's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees its
    // interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static ResultSet resultSet(List<Map.Entry<Long, Integer>> rows) {
        var next = new int[]{-1};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                                                  (proxy, method, args) -> switch (method.getName()) {
                                                      case "next" -> {
                                                          next[0]++;
                                                          yield next[0] < rows.size();
                                                      }
                                                      case "getLong" -> rows.get(next[0]).getKey();
                                                      case "getInt" -> rows.get(next[0]).getValue();
                                                      case "close" -> null;
                                                      default -> throw new UnsupportedOperationException(method.getName());
                                                  });
    }
}
