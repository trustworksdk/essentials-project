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

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * <b>Test-only</b>, in the integration module's test tree but in the engine's package (a split package on the test class
 * path, never in a jar): lets the integration ITs start a processor's subscription with the engine's package-private
 * seams - its mutants and a crash hook - exactly as the engine's own crash ITs do (phase 4 §8).
 */
public final class IntegrationTestBridge {
    private IntegrationTestBridge() {
    }

    /** The page-transaction steps of design phase 8 §9.2 (e) */
    public enum CrashPoint {
        BEFORE_HANDLER,
        AFTER_HANDLER_BEFORE_PROGRESS,
        AFTER_PROGRESS_BEFORE_COMMIT,
        AFTER_COMMIT
    }

    /**
     * Subscribe through the package-private spec path
     *
     * @param engineMutants names of {@link WatermarkMutant}s to switch on
     * @param crashAt       where to crash, or {@code null}
     * @param armed         the crash fires the first time {@code crashAt} is reached while this answers {@code true}
     * @param crashed       set once the crash fired
     */
    public static SubscriptionHandle subscribe(EventLog eventLog, SubscriptionDefinition definition, DeliveryHandler handler, PageTransactionSource source,
                                               Set<String> engineMutants, CrashPoint crashAt, BooleanSupplier armed, AtomicBoolean crashed) throws SQLException {
        var mutants = EnumSet.noneOf(WatermarkMutant.class);
        engineMutants.forEach(name -> mutants.add(WatermarkMutant.valueOf(name)));
        var hooks = new TestSeams.Hooks() {
            private void at(CrashPoint point, List<StoredEvent> page) {
                if (point == crashAt && !page.isEmpty() && !crashed.get() && armed.getAsBoolean()) {
                    crashed.set(true);
                    throw new TestSeams.Crash(point.name());
                }
            }

            @Override
            public void beforePageHandler(String subscriber, List<StoredEvent> page) {
                at(CrashPoint.BEFORE_HANDLER, page);
            }

            @Override
            public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) {
                at(CrashPoint.AFTER_HANDLER_BEFORE_PROGRESS, page);
            }

            @Override
            public void afterProgressBeforeCommit(String subscriber, List<StoredEvent> page, long resumeFrom) {
                at(CrashPoint.AFTER_PROGRESS_BEFORE_COMMIT, page);
            }

            @Override
            public void afterCommit(String subscriber, List<StoredEvent> page, long resumeFrom) {
                at(CrashPoint.AFTER_COMMIT, page);
            }
        };
        var spec = switch (handler) {
            case PeriodicHandler periodic -> EventLog.spec(definition, periodic, null, null);
            case PageHandler page -> EventLog.spec(definition, null, page, source);
        };
        return new DefinedSubscription(definition, eventLog.subscribe(spec.withSeams(new TestSeams(mutants, hooks, null))));
    }

    /**
     * After a crash: wait for the delivery thread to end, then terminate the open page connection's backend so that its
     * transaction dies with it, as a lost JVM's would
     */
    public static void cleanUpCrash(SubscriptionHandle handle, DataSourceExecutor executor) throws Exception {
        var subscription = ((DefinedSubscription) handle).subscription();
        var deadline     = System.nanoTime() + 30_000_000_000L;
        while (subscription.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var connection = subscription.openPageConnection();
        if (connection != null) {
            try {
                executor.execute("SELECT pg_terminate_backend(" + connection.unwrap(org.postgresql.PGConnection.class).getBackendPID() + ")");
            } catch (SQLException connectionAlreadyGone) {
                // released by the source already
            }
        }
    }

    @FunctionalInterface
    public interface DataSourceExecutor {
        void execute(String sql) throws SQLException;
    }
}
