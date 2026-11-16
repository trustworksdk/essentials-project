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

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The engine's progress rows (design §2.5, §3.4; plan §8.2): create or read at start, periodic save as a
 * compare-and-set on the reset epoch. Each call is its own autocommit statement on the engine's {@code DataSource}.
 * Phase 2 claims no lease: the fence parameter is always null (§3.5).
 */
final class ProgressStore {
    private static final Logger log = LoggerFactory.getLogger(ProgressStore.class);

    /**
     * A progress row's key. {@code instanceName} is the configured instance for a non-exclusive subscription (one row
     * per instance; two JVMs with the same instance name overwrite each other's progress, design §3.5), {@code ""} for
     * an exclusive one.
     */
    record Key(String subscriber, String aggregateType, String instanceName) {
        Key {
            requireNonBlank(subscriber, "No subscriber provided");
            requireNonBlank(aggregateType, "No aggregateType provided");
            requireNonNull(instanceName, "No instanceName provided");
        }
    }

    record Progress(long resumeFrom, long resetEpoch, String consistencyMode, String progressModel) {
    }

    /**
     * @param resumeFrom the stored resume point, or the initial one for a new row
     * @param resetEpoch the epoch every save must match
     * @param existed    whether the row was there before
     */
    record Started(long resumeFrom, long resetEpoch, boolean existed) {
    }

    private final DataSource dataSource;

    ProgressStore(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    /**
     * Create the row with {@code initialResumeFrom}, or take the stored one. The mode and progress model are stored;
     * if a stored row had others, the change is logged and the row updated in the same statement (§19 item 17).
     */
    Started start(Key key, boolean exclusive, String consistencyMode, String progressModel, long initialResumeFrom) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.PROGRESS_START)) {
            var p = 1;
            statement.setString(p++, key.subscriber());
            statement.setString(p++, key.aggregateType());
            statement.setString(p++, key.instanceName());
            statement.setString(p++, key.subscriber());
            statement.setString(p++, key.aggregateType());
            statement.setString(p++, key.instanceName());
            statement.setBoolean(p++, exclusive);
            statement.setString(p++, consistencyMode);
            statement.setString(p++, progressModel);
            statement.setLong(p, initialResumeFrom);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var started = new Started(resultSet.getLong(1), resultSet.getLong(2), resultSet.getBoolean(3));
                var oldMode  = resultSet.getString(4);
                var oldModel = resultSet.getString(5);
                if (started.existed() && (!consistencyMode.equals(oldMode) || !progressModel.equals(oldModel))) {
                    log.warn("Subscription {} on {} (instance '{}') was stored as {}/{} and now starts as {}/{}; the progress row is updated and the subscription " +
                                     "resumes from {}", key.subscriber(), key.aggregateType(), key.instanceName(), oldMode, oldModel, consistencyMode, progressModel,
                             started.resumeFrom());
                }
                return started;
            }
        }
    }

    /**
     * @return {@code true} if saved; {@code false} if the row is gone or its reset epoch moved (a superseding reset) -
     * {@link #read(Key)} tells which
     */
    boolean save(Key key, long resumeFrom, long resetEpoch) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.PROGRESS_SAVE)) {
            statement.setLong(1, resumeFrom);
            statement.setString(2, key.subscriber());
            statement.setString(3, key.aggregateType());
            statement.setString(4, key.instanceName());
            statement.setLong(5, resetEpoch);
            statement.setNull(6, Types.BIGINT);
            statement.setNull(7, Types.BIGINT);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * The in-transaction progress update (phase 4 §3.3) on the page's {@code connection}, which is not committed here.
     *
     * @param expected the {@code resume_from} this subscription last committed, or {@code null} for no such guard
     * @return {@code true} if one row was updated; {@code false} if the epoch, the expected value or the row is gone -
     * the caller rolls back and {@link #read(Key)} tells which
     */
    static boolean saveInTransaction(Connection connection, Key key, long resumeFrom, long resetEpoch, Long expected) throws SQLException {
        try (var statement = connection.prepareStatement(expected == null ? EventLogStorage.PROGRESS_SAVE : EventLogStorage.PROGRESS_SAVE_EXPECTED)) {
            statement.setLong(1, resumeFrom);
            statement.setString(2, key.subscriber());
            statement.setString(3, key.aggregateType());
            statement.setString(4, key.instanceName());
            statement.setLong(5, resetEpoch);
            statement.setNull(6, Types.BIGINT);
            statement.setNull(7, Types.BIGINT);
            if (expected != null) {
                statement.setLong(8, expected);
            }
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * {@link #saveInTransaction} in its own autocommit statement: an in-transaction subscription's save of a resume
     * point that moved without a page (a watermark), still guarded by the expected value
     */
    boolean saveExpected(Key key, long resumeFrom, long resetEpoch, Long expected) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return saveInTransaction(connection, key, resumeFrom, resetEpoch, expected);
        }
    }

    Optional<Progress> read(Key key) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.PROGRESS_READ)) {
            statement.setString(1, key.subscriber());
            statement.setString(2, key.aggregateType());
            statement.setString(3, key.instanceName());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(new Progress(resultSet.getLong(1), resultSet.getLong(2), resultSet.getString(3), resultSet.getString(4)))
                                        : Optional.empty();
            }
        }
    }
}
