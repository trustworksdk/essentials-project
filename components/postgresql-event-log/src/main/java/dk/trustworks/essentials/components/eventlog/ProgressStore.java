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
 * <p>
 * Phase 7 (design {@code docs/archive/event-store-engine-phase7.md} §2, §3): an exclusive subscription's row carries a
 * lease; every write of it binds a {@link Fence} (fence and owner), a non-exclusive one binds {@code null}. The lease
 * columns are written only here and by {@link LeaseKeeper} through the statements of {@link EventLogStorage}.
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

    /**
     * A stored row; {@code leaseOwner} and {@code leaseFence} {@code null} when no lease was ever claimed (or, for the
     * owner, after a release)
     */
    record Progress(long resumeFrom, long resetEpoch, String consistencyMode, String progressModel, boolean exclusive, String leaseOwner, Long leaseFence) {
        Progress(long resumeFrom, long resetEpoch, String consistencyMode, String progressModel) {
            this(resumeFrom, resetEpoch, consistencyMode, progressModel, false, null, null);
        }
    }

    /**
     * What a write of an exclusive subscription asserts besides the epoch (§3.1, §3.2): the fence its claim drew and the
     * owner id of its event log. {@code owner} is {@code null} only under the test mutant {@code FENCE_WITHOUT_OWNER}
     */
    record Fence(long fence, String owner) {
        Fence withoutOwner() {
            return new Fence(fence, null);
        }
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
        return start(key, exclusive, consistencyMode, progressModel, initialResumeFrom, null, null);
    }

    /**
     * As {@link #start(Key, boolean, String, String, long)}, storing {@code filterDigest} (phase 8 §2.6: a changed digest
     * is logged at WARN and stored, never refused) and, with {@code headTable}, starting a new row at that event table's
     * {@code max(global_order) + 1} instead of {@code initialResumeFrom} (phase 8 decision 13)
     */
    Started start(Key key, boolean exclusive, String consistencyMode, String progressModel, long initialResumeFrom, String headTable, String filterDigest)
            throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(headTable == null ? EventLogStorage.PROGRESS_START : EventLogStorage.progressStart(headTable))) {
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
            if (headTable == null) {
                statement.setLong(p++, initialResumeFrom);
            }
            statement.setString(p, filterDigest);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var started = new Started(resultSet.getLong(1), resultSet.getLong(2), resultSet.getBoolean(3));
                var oldMode  = resultSet.getString(4);
                var oldModel = resultSet.getString(5);
                if (started.existed() && resultSet.getBoolean(6) != exclusive) {
                    throw new IllegalStateException("Subscription " + key.subscriber() + " on " + key.aggregateType() + " (instance '" + key.instanceName() + "') is stored as " +
                                                            (exclusive ? "non-exclusive" : "exclusive") + " and cannot start as " + (exclusive ? "exclusive" : "non-exclusive"));
                }
                if (started.existed() && (!consistencyMode.equals(oldMode) || !progressModel.equals(oldModel))) {
                    log.warn("Subscription {} on {} (instance '{}') was stored as {}/{} and now starts as {}/{}; the progress row is updated and the subscription " +
                                     "resumes from {}", key.subscriber(), key.aggregateType(), key.instanceName(), oldMode, oldModel, consistencyMode, progressModel,
                             started.resumeFrom());
                }
                var oldDigest = resultSet.getString(7);
                if (started.existed() && !java.util.Objects.equals(oldDigest, filterDigest)) {
                    log.warn("Subscription {} on {} (instance '{}') was stored with filter digest {} and now starts with {}: its event-type or tenant filter " +
                                     "changed. The new digest is stored and delivery continues under the new filter from {}; reset the subscription to replay",
                             key.subscriber(), key.aggregateType(), key.instanceName(), oldDigest, filterDigest, started.resumeFrom());
                }
                return started;
            }
        }
    }

    /** A non-exclusive save: {@link #save(Key, long, Long, Fence)} with no fence */
    boolean save(Key key, long resumeFrom, long resetEpoch) throws SQLException {
        return save(key, resumeFrom, resetEpoch, null);
    }

    /**
     * @param resetEpoch the epoch the resume point was computed in; {@code null} only under mutant {@code SAVE_WITHOUT_EPOCH}
     * @param fence      the owner's fence, {@code null} for a non-exclusive subscription
     * @return {@code true} if saved; {@code false} if the row is gone, its reset epoch moved (a superseding reset) or its
     * lease moved - {@link #read(Key)} tells which
     */
    boolean save(Key key, long resumeFrom, Long resetEpoch, Fence fence) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.PROGRESS_SAVE)) {
            statement.setLong(1, resumeFrom);
            var p = bindKey(statement, 2, key);
            bindGuard(statement, p, resetEpoch, fence);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * The in-transaction progress update (phase 4 §3.3) on the page's {@code connection}, which is not committed here.
     *
     * @param expected the {@code resume_from} this subscription last committed, or {@code null} for no such guard
     * @return {@code true} if one row was updated; {@code false} if the epoch, the fence, the expected value or the row
     * is gone - the caller rolls back and {@link #read(Key)} tells which
     */
    static boolean saveInTransaction(Connection connection, Key key, long resumeFrom, Long resetEpoch, Fence fence, Long expected) throws SQLException {
        try (var statement = connection.prepareStatement(expected == null ? EventLogStorage.PROGRESS_SAVE : EventLogStorage.PROGRESS_SAVE_EXPECTED)) {
            statement.setLong(1, resumeFrom);
            var p = bindKey(statement, 2, key);
            p = bindGuard(statement, p, resetEpoch, fence);
            if (expected != null) {
                statement.setLong(p, expected);
            }
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * {@link #saveInTransaction} in its own autocommit statement: an in-transaction subscription's save of a resume
     * point that moved without a page (a watermark), still guarded by the expected value
     */
    boolean saveExpected(Key key, long resumeFrom, Long resetEpoch, Fence fence, Long expected) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return saveInTransaction(connection, key, resumeFrom, resetEpoch, fence, expected);
        }
    }

    /**
     * Mutant {@code FENCE_READ_BEFORE_WRITE}: an unlocked read of the fence on {@code connection}, as the rejected shape
     * did before an unfenced update
     */
    static boolean fenceMatches(Connection connection, Key key, Fence fence) throws SQLException {
        try (var statement = connection.prepareStatement(EventLogStorage.PROGRESS_FENCE_CHECK)) {
            var p = bindKey(statement, 1, key);
            statement.setLong(p++, fence.fence());
            statement.setString(p, fence.owner());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /**
     * A new owner writes its own mode and model over the ones the claimed row named (§2.2), fenced
     *
     * @return whether the row was updated
     */
    boolean setMode(Key key, String consistencyMode, String progressModel, String filterDigest, long resetEpoch, Fence fence) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.PROGRESS_SET_MODE)) {
            statement.setString(1, consistencyMode);
            statement.setString(2, progressModel);
            statement.setString(3, filterDigest);
            var p = bindKey(statement, 4, key);
            bindGuard(statement, p, resetEpoch, fence);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Release the lease (§2.5); a periodic owner writes its resume point in the same statement, only under the epoch it
     * was computed in.
     *
     * @param resumeFrom the resume point to write, or {@code null} to release only (in-transaction)
     * @param resetEpoch the epoch {@code resumeFrom} was computed in; {@code null} only under mutant {@code RELEASE_WRITES_ANY_EPOCH}
     * @return whether the lease was still this owner's
     */
    boolean release(Key key, Long resumeFrom, Long resetEpoch, Fence fence) throws SQLException {
        requireNonNull(fence, "No fence provided");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(resumeFrom == null ? EventLogStorage.LEASE_RELEASE : EventLogStorage.PROGRESS_SAVE_RELEASE)) {
            var p = 1;
            if (resumeFrom != null) {
                bindNullableLong(statement, p++, resetEpoch);
                statement.setLong(p++, resumeFrom);
            }
            p = bindKey(statement, p, key);
            statement.setLong(p++, fence.fence());
            statement.setString(p, fence.owner());
            return statement.executeUpdate() == 1;
        }
    }

    /** Bind a key's three columns from parameter {@code first}; @return the next parameter */
    static int bindKey(PreparedStatement statement, int first, Key key) throws SQLException {
        statement.setString(first, key.subscriber());
        statement.setString(first + 1, key.aggregateType());
        statement.setString(first + 2, key.instanceName());
        return first + 3;
    }

    /** Bind {@link EventLogStorage#FENCE_GUARD}'s four parameters from {@code first}; @return the next parameter */
    static int bindGuard(PreparedStatement statement, int first, Long resetEpoch, Fence fence) throws SQLException {
        bindNullableLong(statement, first, resetEpoch);
        if (fence == null) {
            statement.setNull(first + 1, Types.BIGINT);
            statement.setNull(first + 2, Types.BIGINT);
            statement.setNull(first + 3, Types.VARCHAR);
        } else {
            statement.setLong(first + 1, fence.fence());
            statement.setLong(first + 2, fence.fence());
            statement.setString(first + 3, fence.owner());
        }
        return first + 4;
    }

    private static void bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    Optional<Progress> read(Key key) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.PROGRESS_READ)) {
            statement.setString(1, key.subscriber());
            statement.setString(2, key.aggregateType());
            statement.setString(3, key.instanceName());
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                var fence     = resultSet.getLong(7);
                var noFence   = resultSet.wasNull();
                return Optional.of(new Progress(resultSet.getLong(1), resultSet.getLong(2), resultSet.getString(3), resultSet.getString(4), resultSet.getBoolean(5),
                                                resultSet.getString(6), noFence ? null : fence));
            }
        }
    }

    /**
     * Reset {@code key} to {@code resumeFrom}: bump the epoch and delete the subscription's parked rows in one
     * transaction, since a rebuild re-delivers everything (design phase 4 §4.5). Package-private until phase 8's reset API.
     *
     * @return whether the row existed
     */
    boolean reset(Key key, long resumeFrom) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var existed = reset(connection, key, resumeFrom);
                connection.commit();
                return existed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * The reset's two statements on {@code connection}, which is not committed here (phase 8 §4.2): the caller's
     * transaction, so a rebuild truncates its view and resets in one commit. The progress row first, then the parked
     * rows - the order the serve transaction locks them in. A row that does not exist is not created.
     *
     * @return whether the row existed
     */
    static boolean reset(Connection connection, Key key, long resumeFrom) throws SQLException {
        int updated;
        try (var statement = connection.prepareStatement(EventLogStorage.PROGRESS_RESET)) {
            statement.setLong(1, resumeFrom);
            bindKey(statement, 2, key);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            return false;
        }
        try (var statement = connection.prepareStatement(EventLogStorage.PARKED_DELETE_ALL)) {
            bindKey(statement, 1, key);
            statement.executeUpdate();
        }
        return true;
    }
}
