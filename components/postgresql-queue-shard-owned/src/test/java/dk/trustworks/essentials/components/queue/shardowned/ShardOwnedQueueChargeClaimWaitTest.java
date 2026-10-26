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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code ShardOwnedQueue.chargeKeysInHandlerBeforeRelease}, the queue's wrapper around an ordered owner's charge before a
 * release, driven on a queue that is never started: no database is touched.
 * <p>
 * A charge that gives up waiting for another release of the owner's lease row ({@link ReleaseClaimWaitTimedOutException})
 * never started, so it is thrown whatever the owner owes by then. The handler it was to charge may return while it waits,
 * and nothing then reads as owed: the wrapper used to take that for a failure after the charge's writes, logged the WARN
 * blaming a {@code QueueObserver} or a connection close and returned, so a release on request went on to release the row
 * while the other release still held its claim, instead of answering {@code CLAIM_WAIT_TIMED_OUT}.
 */
class ShardOwnedQueueChargeClaimWaitTest {

    private static final short  QUEUE_ID                = 1;
    private static final long   SEQ                     = 7L;
    private static final String CHARGING_THREAD         = "charge-before-release";
    /**
     * Part of the WARN {@code orderedChargeFailedWithNothingOwed} logs.
     */
    private static final String NOTHING_OWED_WARNING    = "has nothing left to charge before releasing ordered unit";

    @Test
    // shutdownNow() in the finally ends the charging thread; close() would wait for it with no bound
    @SuppressWarnings("PMD.CloseResource")
    void a_charge_that_gives_up_waiting_for_another_release_is_thrown_though_its_handler_returned_while_it_waited() throws Exception {
        var queue   = queue(new AnsweringDataSource());
        var handler = new CountDownLatch(1);
        var owner   = orderedOwner(handler);
        dispatchOrdered(owner);
        var scope = OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER;
        assertThat(owner.owesReleaseCharge(scope)).as("premise: the message in its handler is owed a charge").isTrue();
        // Another release of the owner's row, its statement still running: it holds this claim until the statement returns
        assertThat(owner.claimReleaseUnlessMoveOwed()).as("premise: the release is claimed, no move owed").isTrue();
        var charging = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name(CHARGING_THREAD).factory());
        var logged   = captureQueueLog();
        try {
            var charge = charging.submit(() -> NetworkWaitBound.call(Duration.ofSeconds(2), () -> chargeBeforeRelease(queue, owner, scope)));
            await().atMost(Duration.ofSeconds(10)).until(ShardOwnedQueueChargeClaimWaitTest::chargeWaitsForTheReleaseClaim);

            handler.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> owner.inFlight() == 0);
            assertThat(owner.owesReleaseCharge(scope)).as("premise: with the handler returned, nothing reads as owed").isFalse();
            assertThat(charge.isDone()).as("premise: the handler returned while the charge still waited").isFalse();

            var thrown = catchExecution(charge);
            assertThat(thrown).as("the claim wait given up is thrown, so a release on request answers CLAIM_WAIT_TIMED_OUT")
                              .isInstanceOf(ReleaseClaimWaitTimedOutException.class);
            assertThat(warnings(logged)).as("not reported as a failure after the charge's writes").isEmpty();
            assertThat(owner.releaseCharge().charged()).as("nothing charged").isZero();
        } finally {
            owner.endReleaseClaim();
            handler.countDown();
            releaseQueueLog(logged);
            charging.shutdownNow();
        }
    }

    // The reflective wrapper carries nothing of its own: the wrapped method's own exception is rethrown as it is
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static OrderedShardOwner.ReleaseCharge chargeBeforeRelease(ShardOwnedQueue queue, OrderedShardOwner owner,
                                                                       OrderedShardOwner.ReleaseChargeScope scope) throws SQLException {
        try {
            var method = ShardOwnedQueue.class.getDeclaredMethod("chargeKeysInHandlerBeforeRelease", LeasedOwner.class,
                                                                 OrderedShardOwner.ReleaseChargeScope.class, String.class);
            method.setAccessible(true);
            return (OrderedShardOwner.ReleaseCharge) method.invoke(queue, owner, scope, "on request");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Throwable catchExecution(Future<?> future) throws InterruptedException, TimeoutException {
        try {
            future.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /**
     * Whether the charging thread is waiting in the ledger for the release claim to end.
     */
    private static boolean chargeWaitsForTheReleaseClaim() {
        return Thread.getAllStackTraces().entrySet().stream()
                     .anyMatch(thread -> CHARGING_THREAD.equals(thread.getKey().getName())
                             && thread.getKey().getState() == Thread.State.TIMED_WAITING
                             && Arrays.stream(thread.getValue()).anyMatch(frame -> "awaitNoReleaseClaimed".equals(frame.getMethodName())));
    }

    private static ShardOwnedQueue queue(DataSource dataSource) {
        return ShardOwnedQueue.builder()
                              .setDataSource(dataSource)
                              .setQueueId(QUEUE_ID)
                              .setShardCount(4)
                              .setInstanceId("charge-claim-wait")
                              .build();
    }

    private static OrderedShardOwner orderedOwner(CountDownLatch handler) {
        return new OrderedShardOwner(new ShardOwnedStorage(new AnsweringDataSource(), QUEUE_ID), 3, 1L, ShardOwnerSettings.builder().build(),
                                     (id, key, payload, type) -> {
                                         try {
                                             if (!handler.await(10, TimeUnit.SECONDS)) {
                                                 throw new IllegalStateException("not let return");
                                             }
                                         } catch (InterruptedException e) {
                                             throw new IllegalStateException(e);
                                         }
                                     }, new ShardOwnerMetrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-under-test",
                                     new ShardWakeup(), new HandlerDispatch(command -> Thread.ofPlatform().daemon().start(command), 8), false);
    }

    /**
     * Hold message {@link #SEQ} of key {@code K} and dispatch it, as a read and the pump do. Set directly: reaching it
     * through a read would need a database.
     */
    @SuppressWarnings("unchecked")
    private static void dispatchOrdered(OrderedShardOwner owner) throws Exception {
        synchronized (field(owner, "stateLock")) {
            ((Map<String, NavigableMap<Long, ShardOwnedStorage.OrderedRow>>) field(owner, "readyByKey"))
                    .computeIfAbsent("K", key -> new TreeMap<>()).put(5L, new ShardOwnedStorage.OrderedRow(SEQ, "K", 5L, new byte[0], 0));
        }
        var dispatch = OrderedShardOwner.class.getDeclaredMethod("dispatchReadyKeys");
        dispatch.setAccessible(true);
        assertThat((int) dispatch.invoke(owner)).as("premise: the message is in its handler").isEqualTo(1);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = OrderedShardOwner.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ListAppender<ILoggingEvent> captureQueueLog() {
        var logged = new ListAppender<ILoggingEvent>();
        logged.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class)).addAppender(logged);
        return logged;
    }

    private static void releaseQueueLog(ListAppender<ILoggingEvent> logged) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class)).detachAppender(logged);
        logged.stop();
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> logged) {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == Level.WARN)
                          .map(ILoggingEvent::getFormattedMessage)
                          .filter(message -> message.contains(NOTHING_OWED_WARNING))
                          .toList();
    }

    /**
     * A {@code DataSource} handing out connections that may be bounded ({@link NetworkWaitBound}) and closed, and refuse
     * anything else: the charge here gives up before it sends a statement.
     */
    // DataSource's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not. Identity is the correct equals() for a proxy.
    @SuppressWarnings({"PMD.UseProperClassLoader", "PMD.CompareObjectsWithEquals"})
    private static final class AnsweringDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                                       (proxy, method, args) -> switch (method.getName()) {
                                                           case "getNetworkTimeout" -> 0;
                                                           case "setNetworkTimeout", "close" -> null;
                                                           case "isClosed" -> false;
                                                           case "hashCode" -> System.identityHashCode(proxy);
                                                           case "equals" -> proxy == args[0];
                                                           case "toString" -> "answering connection";
                                                           default -> throw new SQLException("no database here: " + method.getName());
                                                       });
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
