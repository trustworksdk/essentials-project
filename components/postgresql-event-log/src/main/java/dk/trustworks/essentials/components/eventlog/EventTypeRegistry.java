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

import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.components.eventlog.EventLogStorage.*;
import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Interns event-type names as {@code smallint} ids in {@code el_event_types} (design §2.3, decision 2).
 * <p>
 * A name the cache does not know is looked for in the table first (reloading it whole), and only then registered with
 * {@code INSERT ... ON CONFLICT (name) DO NOTHING RETURNING id} followed by a {@code SELECT} when a concurrent
 * registration won. Registration runs on a connection of the engine's own {@code DataSource}, in autocommit, so the id
 * is committed before any append that uses it is sent - an append that rolls back never takes a registered type with
 * it. The cache is filled only from committed rows. The look-up first matters: {@code ON CONFLICT} draws an identity
 * value even when it inserts nothing, and the id space has only {@value Short#MAX_VALUE} values.
 * <p>
 * The {@code DataSource} must hand out connections that are not bound to a caller's transaction.
 */
public final class EventTypeRegistry {
    private final DataSource                         dataSource;
    private final ConcurrentHashMap<String, Short>   idsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Short, String>   namesById = new ConcurrentHashMap<>();

    /**
     * @param dataSource the engine's database
     */
    public EventTypeRegistry(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    /**
     * @return the id of {@code eventType}, registering it in its own committed transaction if it is new
     */
    public short idOf(String eventType) throws SQLException {
        requireNonBlank(eventType, "No eventType provided");
        var id = idsByName.get(eventType);
        if (id != null) {
            return id;
        }
        reload();
        id = idsByName.get(eventType);
        if (id != null) {
            return id;
        }
        return register(eventType);
    }

    /**
     * @return the name of the event type with {@code id}
     * @throws IllegalStateException if no such id is registered
     */
    public String nameOf(short id) throws SQLException {
        var name = namesById.get(id);
        if (name != null) {
            return name;
        }
        reload();
        name = namesById.get(id);
        if (name == null) {
            throw new IllegalStateException("Unknown event type id " + id + " in " + EventLogSchema.EVENT_TYPES_TABLE);
        }
        return name;
    }

    private short register(String eventType) throws SQLException {
        short id;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var insert = connection.prepareStatement(REGISTER_EVENT_TYPE)) {
                insert.setString(1, eventType);
                try (var resultSet = insert.executeQuery()) {
                    if (resultSet.next()) {
                        id = resultSet.getShort(1);
                    } else {
                        id = select(connection, eventType);
                    }
                }
            } catch (SQLException e) {
                if ("2200H".equals(e.getSQLState())) {
                    throw new IllegalStateException("Cannot register event type '" + eventType + "': " + EventLogSchema.EVENT_TYPES_TABLE +
                                                            " has used all " + Short.MAX_VALUE + " ids", e);
                }
                throw e;
            }
        }
        // Autocommit: the row is committed by now
        cache(eventType, id);
        return id;
    }

    private static short select(Connection connection, String eventType) throws SQLException {
        try (var select = connection.prepareStatement(SELECT_EVENT_TYPE_ID)) {
            select.setString(1, eventType);
            try (var resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Event type '" + eventType + "' was neither inserted nor found");
                }
                return resultSet.getShort(1);
            }
        }
    }

    private void reload() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SELECT_EVENT_TYPES);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                cache(resultSet.getString(2), resultSet.getShort(1));
            }
        }
    }

    private void cache(String name, short id) {
        namesById.put(id, name);
        idsByName.put(name, id);
    }
}
