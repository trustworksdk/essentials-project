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
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.IMPORT_PROGRESS_OVERWRITES;
import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.RESET_OUTSIDE_CALLER_TRANSACTION;
import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * {@link EventLogAdmin} on the event log's {@code DataSource} (design {@code docs/plans/event-store-engine-phase8.md} §4)
 */
final class DatabaseEventLogAdmin implements EventLogAdmin {
    private static final Logger log = LoggerFactory.getLogger(DatabaseEventLogAdmin.class);

    private final DataSource                                   dataSource;
    private final EventLogSettings                             settings;
    private final ProgressStore                                progressStore;
    /** This event log's running subscriptions, to serve a request locally (§4.3 step 2) */
    private final Supplier<List<Subscription>>                 subscriptions;
    private final Supplier<TestSeams>                          seams;

    DatabaseEventLogAdmin(DataSource dataSource, EventLogSettings settings, ProgressStore progressStore, Supplier<List<Subscription>> subscriptions,
                          Supplier<TestSeams> seams) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.progressStore = requireNonNull(progressStore, "No progressStore provided");
        this.subscriptions = requireNonNull(subscriptions, "No subscriptions provided");
        this.seams = requireNonNull(seams, "No seams provided");
    }

    @Override
    public List<SubscriptionRow> subscriptions(Optional<String> aggregateType) throws SQLException {
        requireNonNull(aggregateType, "No aggregateType provided");
        return list(aggregateType.orElse(null), null);
    }

    @Override
    public Optional<SubscriptionRow> subscription(ProgressKey key) throws SQLException {
        requireNonNull(key, "No key provided");
        return list(key.aggregateType(), key).stream().filter(row -> row.key().equals(key)).findFirst();
    }

    private List<SubscriptionRow> list(String aggregateType, ProgressKey only) throws SQLException {
        var rows = new ArrayList<SubscriptionRow>();
        var sql  = only == null ? EventLogStorage.ADMIN_SUBSCRIPTIONS : EventLogStorage.ADMIN_SUBSCRIPTION;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            if (only == null) {
                statement.setString(1, aggregateType);
                statement.setString(2, aggregateType);
                statement.setInt(3, settings.adminListLimit());
            } else {
                ProgressStore.bindKey(statement, 1, only.internal());
            }
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var fence = resultSet.getLong(11);
                    var noFence = resultSet.wasNull();
                    var lag = resultSet.getLong(16);
                    var noLag = resultSet.wasNull();
                    rows.add(new SubscriptionRow(new ProgressKey(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)), resultSet.getBoolean(4),
                                                 resultSet.getString(5), resultSet.getString(6), resultSet.getLong(7), resultSet.getLong(8),
                                                 Optional.ofNullable(resultSet.getString(9)), Optional.ofNullable(resultSet.getObject(10, OffsetDateTime.class)),
                                                 noFence ? OptionalLong.empty() : OptionalLong.of(fence), resultSet.getObject(12, OffsetDateTime.class),
                                                 Optional.ofNullable(resultSet.getString(13)), resultSet.getLong(14), resultSet.getLong(15),
                                                 noLag ? OptionalLong.empty() : OptionalLong.of(lag)));
                }
            }
        }
        return rows;
    }

    @Override
    public ParkedPage parked(ProgressKey key, long afterGlobalOrder, int limit) throws SQLException {
        requireNonNull(key, "No key provided");
        requireTrue(limit > 0, "limit must be > 0");
        var rows = new ArrayList<ParkedRow>();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.ADMIN_PARKED)) {
            var p = ProgressStore.bindKey(statement, 1, key.internal());
            statement.setLong(p++, afterGlobalOrder);
            statement.setInt(p, limit);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var blockedBy = resultSet.getLong(3);
                    var head      = resultSet.wasNull();
                    rows.add(new ParkedRow(resultSet.getLong(1), resultSet.getString(2), head ? OptionalLong.empty() : OptionalLong.of(blockedBy), resultSet.getInt(4),
                                           Optional.ofNullable(resultSet.getString(5)), Optional.ofNullable(resultSet.getString(6)),
                                           resultSet.getObject(7, OffsetDateTime.class), Optional.ofNullable(resultSet.getString(8)),
                                           Optional.ofNullable(resultSet.getObject(9, OffsetDateTime.class))));
                }
            }
        }
        return new ParkedPage(rows, rows.size() == limit ? OptionalLong.of(rows.getLast().globalOrder()) : OptionalLong.empty());
    }

    @Override
    public boolean reset(ProgressKey key, long resumeFrom) throws SQLException {
        requireNonNull(key, "No key provided");
        requireTrue(resumeFrom >= 1, "resumeFrom must be >= 1");
        return progressStore.reset(key.internal(), resumeFrom);
    }

    @Override
    public boolean reset(ProgressKey key, long resumeFrom, Connection connection) throws SQLException {
        requireNonNull(key, "No key provided");
        requireNonNull(connection, "No connection provided");
        requireTrue(resumeFrom >= 1, "resumeFrom must be >= 1");
        if (seams.get().has(RESET_OUTSIDE_CALLER_TRANSACTION)) {
            return progressStore.reset(key.internal(), resumeFrom);
        }
        return ProgressStore.reset(connection, key.internal(), resumeFrom);
    }

    @Override
    public ParkedRequestResult retryParked(ProgressKey key, long head) throws SQLException {
        return request(key, head, false);
    }

    @Override
    public ParkedRequestResult discardParked(ProgressKey key, long head) throws SQLException {
        return request(key, head, true);
    }

    private ParkedRequestResult request(ProgressKey key, long head, boolean discard) throws SQLException {
        requireNonNull(key, "No key provided");
        long exists;
        long stored;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARKED_REQUEST)) {
            var p = ProgressStore.bindKey(statement, 1, key.internal());
            statement.setString(p++, discard ? "DISCARD" : "RETRY");
            p = ProgressStore.bindKey(statement, p, key.internal());
            statement.setLong(p, head);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                exists = resultSet.getLong(1);
                stored = resultSet.getLong(2);
            }
        }
        if (exists == 0) {
            return ParkedRequestResult.NO_SUCH_SUBSCRIPTION;
        }
        if (stored == 0) {
            return ParkedRequestResult.NOT_A_HEAD;
        }
        var local = subscriptions.get().stream().filter(s -> s.key().equals(key.internal()) && s.servesParked()).findFirst();
        if (local.isEmpty()) {
            return ParkedRequestResult.ACCEPTED;
        }
        var future = discard ? local.get().discardParked(head) : local.get().retryParked(head);
        try {
            return future.get(settings.effectiveParkedCheckInterval().toMillis(), TimeUnit.MILLISECONDS) ? ParkedRequestResult.DONE : ParkedRequestResult.FAILED;
        } catch (TimeoutException | ExecutionException e) {
            return ParkedRequestResult.ACCEPTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ParkedRequestResult.ACCEPTED;
        }
    }

    @Override
    public boolean deleteRetiredInstance(ProgressKey key) throws SQLException {
        requireNonNull(key, "No key provided");
        requireTrue(!key.instanceName().isBlank(), "Only a per-instance row can be deleted as retired");
        if (subscriptions.get().stream().anyMatch(s -> s.key().equals(key.internal()) && s.isRunning())) {
            throw new IllegalStateException("Subscription " + key + " runs in this EventLog; stop it before deleting its row");
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.ADMIN_DELETE_RETIRED)) {
            var p = ProgressStore.bindKey(statement, 1, key.internal());
            ProgressStore.bindKey(statement, p, key.internal());
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var deleted = resultSet.getLong(1) > 0;
                if (deleted) {
                    log.info("Deleted the progress row and parked rows of retired instance {}", key);
                }
                return deleted;
            }
        }
    }

    @Override
    public long importProgress(SubscriptionDefinition definition, ProgressModel model, String instanceName, long resumeFrom, Connection connection) throws SQLException {
        requireNonNull(definition, "No definition provided");
        requireNonNull(model, "No model provided");
        requireNonNull(instanceName, "No instanceName provided");
        requireNonNull(connection, "No connection provided");
        requireTrue(resumeFrom >= 1, "resumeFrom must be >= 1");
        var exclusive = definition.ownership() instanceof Ownership.Exclusive;
        if (exclusive) {
            requireTrue(instanceName.isEmpty(), "An exclusive subscription has one row, under the instance name \"\"");
        } else {
            requireTrue(!instanceName.isBlank(), "A per-instance subscription's row needs a non-blank instance name");
        }
        var key = new ProgressStore.Key(definition.subscriber(), definition.aggregateType(), instanceName);
        var sql = seams.get().has(IMPORT_PROGRESS_OVERWRITES) ? EventLogStorage.PROGRESS_IMPORT_OVERWRITING : EventLogStorage.PROGRESS_IMPORT;
        try (var statement = connection.prepareStatement(sql)) {
            var p = ProgressStore.bindKey(statement, 1, key);
            statement.setBoolean(p++, exclusive);
            statement.setString(p++, definition.consistency().name());
            statement.setString(p++, model.name());
            statement.setLong(p++, resumeFrom);
            statement.setString(p, definition.filterDigest());
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    log.info("Imported progress row of {} at resume point {}", key, resumeFrom);
                    return resultSet.getLong(1);
                }
            }
        }
        // The row existed: its stored resume point, read in a statement of its own so a row a concurrent import
        // committed while this one waited on it is seen
        try (var statement = connection.prepareStatement(EventLogStorage.PROGRESS_RESUME_FROM)) {
            ProgressStore.bindKey(statement, 1, key);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("The progress row of " + key + " neither was inserted nor exists");
                }
                return resultSet.getLong(1);
            }
        }
    }
}
