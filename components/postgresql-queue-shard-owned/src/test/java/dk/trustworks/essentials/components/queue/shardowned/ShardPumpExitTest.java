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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.*;
import org.postgresql.PGConnection;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * How a pump's exit treats the shards whose takeover was failing when it exited.
 * <p>
 * On an orderly stop ({@code running} cleared) the pump serves none of its shards any more, so none of them is awaiting
 * a takeover there. A pump that DIES while the runtime runs is different: nothing restarts it and the heartbeat keeps
 * renewing its shards' leases, so its stalled shards are still leased and unserved. Zeroing
 * {@code shardsAwaitingTakeover} then made the one metric for that state read healthy exactly when it had got worse,
 * and the thread's end was silent, because the runtime submits the pump to an executor whose {@code Future} nobody reads.
 */
class ShardPumpExitTest {
    /**
     * A pump's storage handle is bound to no queue in particular, as in {@code ShardRuntime}: it only opens connections.
     */
    private static final short PUMP_QUEUE_ID = 0;

    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger pumpLogger;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        pumpLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardPump.class);
        pumpLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        pumpLogger.detachAppender(logged);
    }

    @Test
    void a_pump_that_dies_while_running_keeps_its_stalled_shards_counted_and_says_so_at_error() {
        var told    = new CopyOnWriteArrayList<String>();
        var metrics = new ShardOwnerMetrics(observer(told));
        var running = new AtomicBoolean(true);
        var pump    = pump(running);
        pump.add(new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_FAILS));
        pump.add(new StubOwner((short) 7, 2, metrics, Behaviour.DIES));

        assertThatThrownBy(pump::run).as("the pump's death is not swallowed").isSameAs(StubOwner.DEATH);

        assertThat(running.get()).as("premise: the runtime is still running").isTrue();
        assertThat(metrics.shardsAwaitingTakeover.get()).as("shard 1 is still leased and still unserved").isEqualTo(1);
        assertThat(told).as("the observer is not told the stall ended").containsExactly("UNORDERED/1=true");
        var died = logged.list.stream()
                              .filter(event -> event.getLevel() == Level.ERROR)
                              .filter(event -> event.getFormattedMessage().contains("pump-0 died"))
                              .toList();
        assertThat(died).hasSize(1);
        assertThat(died.getFirst().getFormattedMessage())
                .contains("2 shard(s)")
                .contains("stay leased to this instance")
                .contains("shardsAwaitingTakeover");
        assertThat(died.getFirst().getThrowableProxy().getMessage()).isEqualTo(StubOwner.DEATH.getMessage());
    }

    @Test
    void a_pump_stopped_in_order_no_longer_counts_its_stalled_shards() throws InterruptedException {
        var told    = new CopyOnWriteArrayList<String>();
        var metrics = new ShardOwnerMetrics(observer(told));
        var running = new AtomicBoolean(true);
        var pump    = pump(running);
        pump.add(new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_FAILS));
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        await().atMost(Duration.ofSeconds(10)).until(() -> metrics.shardsAwaitingTakeover.get() == 1);

        running.set(false);
        pump.wakeup().signal();
        assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();

        assertThat(metrics.shardsAwaitingTakeover.get()).as("none of its shards is served by it any more").isZero();
        assertThat(told).containsExactly("UNORDERED/1=true", "UNORDERED/1=false");
        assertThat(logged.list.stream().map(ILoggingEvent::getFormattedMessage)).noneMatch(message -> message.contains("died"));
    }

    /**
     * The stop ends the leases the dead pump was keeping, so its stalled shards are awaiting nothing any more. Before it,
     * nothing ever forgot them: the metrics outlive the runtime, so the restart the death's ERROR recommends left
     * {@code shardsAwaitingTakeover} (and the observer's gauge) stuck above zero for the rest of the process.
     */
    @Test
    void stopping_the_runtime_forgets_the_stalled_shards_of_a_pump_that_had_died() {
        var told    = new CopyOnWriteArrayList<String>();
        var metrics = new ShardOwnerMetrics(observer(told));
        var runtime = new ShardRuntime(listenableDataSource(), onePump(), new ShardOwnerMetrics());
        try {
            runtime.register((short) 7, "unordered", 1, new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_FAILS));
            await().atMost(Duration.ofSeconds(10)).until(() -> metrics.shardsAwaitingTakeover.get() == 1);
            runtime.register((short) 7, "unordered", 2, new StubOwner((short) 7, 2, metrics, Behaviour.DIES));
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> logged.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                           && event.getFormattedMessage().contains("pump-0 died")));
            assertThat(metrics.shardsAwaitingTakeover.get()).as("premise: the dead pump kept its stalled shard counted").isEqualTo(1);
        } finally {
            runtime.stop();
        }

        assertThat(metrics.shardsAwaitingTakeover.get()).as("the stop ended the lease the dead pump was keeping").isZero();
        assertThat(told).containsExactly("UNORDERED/1=true", "UNORDERED/1=false");
    }

    private static ShardOwnerSettings onePump() {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(), d.holeExpiry(),
                                      d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(), d.pollBackstop(),
                                      d.maxSweepInterval(), 1, d.shedGrace(), d.leaseTtl(), d.watermarkCap());
    }

    private static ShardPump pump(AtomicBoolean running) {
        return new ShardPump(new ShardOwnedStorage(healthyDataSource(), PUMP_QUEUE_ID), ShardOwnerSettings.defaults(),
                             new ShardOwnerMetrics(), running, new AtomicBoolean(false), "pump-0", Optional::empty);
    }

    private static QueueObserver observer(List<String> told) {
        return new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                told.add(lane + "/" + shard + "=" + stalled);
            }
        };
    }

    /**
     * Hands out connections that are valid and do nothing: the stub owners never issue a statement.
     */
    private static DataSource healthyDataSource() {
        var connection = (Connection) Proxy.newProxyInstance(ShardPumpExitTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> true;
                                                                 case "close" -> null;
                                                                 case "isClosed" -> false;
                                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                                 case "equals" -> proxy == args[0];
                                                                 case "toString" -> "stub connection";
                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                             });
        return (DataSource) Proxy.newProxyInstance(ShardPumpExitTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> connection;
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "stub data source";
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    /**
     * Like {@link #healthyDataSource()}, and also lets a {@link ShardRuntime}'s wake-up listener {@code LISTEN} and wait
     * for notifications that never come, so it neither fails nor logs a reconnect every 200 ms.
     */
    private static DataSource listenableDataSource() {
        var loader    = ShardPumpExitTest.class.getClassLoader();
        var statement = (Statement) Proxy.newProxyInstance(loader, new Class<?>[]{Statement.class},
                                                           (proxy, method, args) -> switch (method.getName()) {
                                                               case "execute" -> false;
                                                               case "close" -> null;
                                                               default -> throw new UnsupportedOperationException(method.getName());
                                                           });
        var notifications = (PGConnection) Proxy.newProxyInstance(loader, new Class<?>[]{PGConnection.class},
                                                                  (proxy, method, args) -> {
                                                                      if (!"getNotifications".equals(method.getName())) {
                                                                          throw new UnsupportedOperationException(method.getName());
                                                                      }
                                                                      try {
                                                                          Thread.sleep(args == null ? 200 : (Integer) args[0]);
                                                                      } catch (InterruptedException e) {
                                                                          Thread.currentThread().interrupt();
                                                                      }
                                                                      return null;
                                                                  });
        var connection = (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> true;
                                                                 case "close" -> null;
                                                                 case "isClosed" -> false;
                                                                 case "createStatement" -> statement;
                                                                 case "unwrap" -> notifications;
                                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                                 case "equals" -> proxy == args[0];
                                                                 case "toString" -> "stub listenable connection";
                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                             });
        return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> connection;
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "stub listenable data source";
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    private enum Behaviour {
        /**
         * Its takeover throws every time: a stalled shard.
         */
        TAKEOVER_FAILS,
        /**
         * Taken over, then throws an {@code Error} from {@code needsAttention}, which no per-owner guard catches.
         */
        DIES
    }

    private record StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics, Behaviour behaviour) implements LeasedOwner {
        static final AssertionError DEATH = new AssertionError("pump bug");

        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            if (behaviour == Behaviour.DIES) {
                throw DEATH;
            }
            return false;
        }

        @Override
        public void onTakeover(Connection connection) {
            if (behaviour == Behaviour.TAKEOVER_FAILS) {
                throw new IllegalStateException("takeover refused");
            }
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public long fence() {
            return 1;
        }

        @Override
        public String lane() {
            return "unordered";
        }

        @Override
        public boolean leaseHeld() {
            return true;
        }

        @Override
        public void beginDraining() {
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void onLeaseEnded(LeaseEnd reason) {
        }
    }
}
