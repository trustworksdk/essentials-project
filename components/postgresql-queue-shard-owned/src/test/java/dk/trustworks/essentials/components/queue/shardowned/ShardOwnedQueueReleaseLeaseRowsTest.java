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
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

/**
 * {@code ShardOwnedQueue.releaseLeaseRows}, the one method that gives an owner's lease row up, driven on a queue that is
 * never started, over a {@code DataSource} the test answers: no database is touched.
 * <ul>
 *     <li>A call whose every owner still owes a dead-letter move ({@link LeasedOwner#owesDeadLetterMove()}) takes no
 *     connection, answers with nothing released and logs the WARN that it left them out, even when the pool has no
 *     connection to give. It used to take the statement's connection before it asked any owner: each heartbeat tick then
 *     checked one out only to close it unused, and with the pool or database unavailable the call threw before any owner
 *     was asked, so the owners left out lost their WARN for as long as the outage lasted.</li>
 *     <li>An end of a release claim ({@link LeasedOwner#endReleaseClaim()}) that throws after the release statement has
 *     returned marks no owner in doubt: the rows were released, so every owner the statement covered reaches
 *     {@link LeasedOwner.LeaseRow#RELEASED}, and the failure is rethrown after that. It used to be caught with the
 *     statement's own failures, so every owner was marked in doubt and left {@code HELD} while its row was released.</li>
 * </ul>
 * The method is private, so it is called by reflection.
 */
class ShardOwnedQueueReleaseLeaseRowsTest {

    private static final short QUEUE_ID = 1;

    /**
     * Part of the WARN {@code releaseLeaseRows} logs for the owners it leaves out.
     */
    private static final String OWED_MOVE_RELEASE_WARNING = "whose move to the dead-letter table is still to be made, and a unit is released only once";

    @Test
    void a_release_whose_every_owner_owes_a_dead_letter_move_takes_no_connection_and_warns_even_when_the_pool_has_none() throws Throwable {
        var dataSource = new AnsweringDataSource(false);
        var queue      = queue(dataSource);
        var owing      = new StandInOwner(0);
        owing.owes.set(true);
        var otherOwing = new StandInOwner(1);
        otherOwing.owes.set(true);
        var logged = captureQueueLog();
        try {
            var release = releaseLeaseRows(queue, List.of(owing, otherOwing));

            assertThat(dataSource.connectionsAsked.get()).as("no connection asked for: no owner is to be released").isZero();
            assertThat(releasedOwners(release)).as("nothing released").isEmpty();
            assertThat(warnings(logged)).as("each owner left out is WARNed of").hasSize(2);
            assertThat(List.of(owing, otherOwing)).as("left as they were: held, not in doubt, nothing claimed")
                                                  .allSatisfy(owner -> {
                                                      assertThat(owner.leaseRow()).isEqualTo(LeasedOwner.LeaseRow.HELD);
                                                      assertThat(owner.releaseInDoubt()).isFalse();
                                                      assertThat(owner.claimed.get()).isZero();
                                                  });
        } finally {
            releaseQueueLog(logged);
        }
    }

    @Test
    void a_release_with_an_owner_to_release_still_warns_of_the_owners_owing_a_move_when_the_pool_has_no_connection() {
        var dataSource = new AnsweringDataSource(false);
        var queue      = queue(dataSource);
        var owing      = new StandInOwner(0);
        owing.owes.set(true);
        var clear  = new StandInOwner(1);
        var logged = captureQueueLog();
        try {
            assertThatThrownBy(() -> releaseLeaseRows(queue, List.of(owing, clear)))
                    .as("the pool's refusal is rethrown").isInstanceOf(SQLException.class);

            assertThat(dataSource.connectionsAsked.get()).as("premise: a connection was asked for").isEqualTo(1);
            assertThat(warnings(logged)).as("the owner left out is WARNed of all the same").hasSize(1);
            assertThat(clear.claimed.get()).as("no owner claimed before the connection was had").isZero();
            assertThat(clear.releaseInDoubt()).as("nothing was sent, so nothing is in doubt").isFalse();
            assertThat(clear.leaseRow()).isEqualTo(LeasedOwner.LeaseRow.HELD);
        } finally {
            releaseQueueLog(logged);
        }
    }

    @Test
    void a_claim_end_that_throws_after_the_release_statement_returned_releases_every_owner_and_marks_none_in_doubt() {
        var dataSource = new AnsweringDataSource(true);
        var queue      = queue(dataSource);
        var failing    = new StandInOwner(0);
        failing.endFailure.set(new IllegalStateException("simulated unbalanced release claim"));
        var other = new StandInOwner(1);

        assertThatThrownBy(() -> releaseLeaseRows(queue, List.of(failing, other)))
                .as("the claim-ending failure still propagates").isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated unbalanced release claim");

        assertThat(dataSource.statementsRun.get()).as("premise: the release statement ran and returned").isEqualTo(1);
        assertThat(List.of(failing, other)).as("every owner the statement covered is released, none marked in doubt")
                                           .allSatisfy(owner -> {
                                               assertThat(owner.leaseRow()).isEqualTo(LeasedOwner.LeaseRow.RELEASED);
                                               assertThat(owner.releaseInDoubt()).isFalse();
                                           });
        assertThat(other.ended.get()).as("the other owner's claim ended too").isEqualTo(other.claimed.get()).isEqualTo(1);
    }

    private static ShardOwnedQueue queue(DataSource dataSource) {
        return ShardOwnedQueue.builder()
                              .setDataSource(dataSource)
                              .setQueueId(QUEUE_ID)
                              .setShardCount(4)
                              .setInstanceId("release-lease-rows")
                              .build();
    }

    /**
     * Call the queue's private {@code releaseLeaseRows}, rethrowing what it threw.
     */
    private static Object releaseLeaseRows(ShardOwnedQueue queue, List<? extends LeasedOwner> owners) throws Throwable {
        var method = ShardOwnedQueue.class.getDeclaredMethod("releaseLeaseRows", List.class);
        method.setAccessible(true);
        try {
            return method.invoke(queue, owners);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<LeasedOwner> releasedOwners(Object release) throws Exception {
        var owners = release.getClass().getDeclaredMethod("owners");
        owners.setAccessible(true);
        return (List<LeasedOwner>) owners.invoke(release);
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
                          .filter(message -> message.contains(OWED_MOVE_RELEASE_WARNING))
                          .toList();
    }

    /**
     * An unordered owner of {@link #QUEUE_ID} under fence 1 that reads as owing a dead-letter move while {@link #owes} is
     * set, claims when it owes nothing, and throws {@link #endFailure} when a claim of it ends, if set; with a lease row
     * state and a WARN rate limit of its own, as the real owners keep.
     */
    private static final class StandInOwner implements LeasedOwner {
        private final ShardOwnerMetrics                     metrics    = new ShardOwnerMetrics();
        private final int                                   shard;
        private final LeaseRowState                         leaseRow   = new LeaseRowState();
        private final OwedMoveReleaseRefusals               refusals   = new OwedMoveReleaseRefusals();
        final         AtomicBoolean                         owes       = new AtomicBoolean();
        final         AtomicInteger                         claimed    = new AtomicInteger();
        final         AtomicInteger                         ended      = new AtomicInteger();
        final         AtomicReference<RuntimeException>     endFailure = new AtomicReference<>();

        StandInOwner(int shard) {
            this.shard = shard;
        }

        @Override
        public boolean owesDeadLetterMove() {
            return owes.get();
        }

        @Override
        public boolean claimReleaseUnlessMoveOwed() {
            if (owesDeadLetterMove()) {
                return false;
            }
            claimed.incrementAndGet();
            return true;
        }

        @Override
        public void endReleaseClaim() {
            ended.incrementAndGet();
            var failure = endFailure.get();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public OwedMoveReleaseRefusals owedMoveReleaseRefusals() {
            return refusals;
        }

        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            return false;
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public int shard() {
            return shard;
        }

        @Override
        public short queueId() {
            return QUEUE_ID;
        }

        @Override
        public ShardOwnerMetrics ownerMetrics() {
            return metrics;
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
        public LeaseRow leaseRow() {
            return leaseRow.get();
        }

        @Override
        public void endKeepingLeaseRow() {
            leaseRow.advanceTo(LeaseRow.ENDED_ROW_HELD);
        }

        @Override
        public void markReleaseInDoubt() {
            leaseRow.markReleaseInDoubt();
        }

        @Override
        public boolean releaseInDoubt() {
            return leaseRow.releaseInDoubt();
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
            leaseRow.advanceTo(LeaseRow.RELEASED);
        }
    }

    /**
     * Counts the connections asked for. Refusing, it throws as a pool with no connection to give does (SQL state
     * {@code 08001}); answering, it hands out a connection on which the release statement returns one row per unit.
     */
    private static final class AnsweringDataSource implements DataSource {
        private final boolean       answers;
        final         AtomicInteger connectionsAsked = new AtomicInteger();
        final         AtomicInteger statementsRun    = new AtomicInteger();

        AnsweringDataSource(boolean answers) {
            this.answers = answers;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionsAsked.incrementAndGet();
            if (!answers) {
                throw new SQLException("simulated pool with no connection to give", "08001");
            }
            return proxy(Connection.class, (method, args) -> switch (method.getName()) {
                case "prepareStatement" -> proxy(PreparedStatement.class, (statementMethod, statementArgs) -> switch (statementMethod.getName()) {
                    case "executeUpdate" -> {
                        statementsRun.incrementAndGet();
                        yield 2;
                    }
                    default -> neutral(statementMethod);
                });
                case "createArrayOf" -> proxy(java.sql.Array.class, (arrayMethod, arrayArgs) -> neutral(arrayMethod));
                case "isClosed" -> false;
                default -> neutral(method);
            });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        private interface Answer {
            Object answer(Method method, Object... args);
        }

        // The interface's own loader, not the thread's context loader: a proxy class must be defined in a loader that
        // sees its interface, which the context loader need not.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private static <T> T proxy(Class<T> type, Answer answer) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> answer.answer(method, args)));
        }

        /**
         * What a method the release does not depend on answers: nothing, zero or {@code false}.
         */
        private static Object neutral(Method method) {
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
}
