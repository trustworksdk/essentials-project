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
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The per-database cap on catch-up reads: one fair {@link Semaphore} per database per
 * JVM, shared by every {@link EventLog} on that database. A delivery thread takes a permit for one catch-up, skip-ahead,
 * bridge or deferred-bridge statement - before it borrows a connection - and returns it after the result set is closed
 * and the connection returned, before any row is handled. The reader loop, page transactions, progress saves,
 * parked-event statements and type-id lookups take none.
 */
final class CatchUpPermits {
    private static final Logger                                   log      = LoggerFactory.getLogger(CatchUpPermits.class);
    private static final ConcurrentHashMap<String, CatchUpPermits> REGISTRY = new ConcurrentHashMap<>();

    private final String    database;
    private final int       size;
    private final Semaphore semaphore;

    CatchUpPermits(String database, int size) {
        this.database = requireNonNull(database, "No database provided");
        requireTrue(size > 0, "size must be > 0");
        this.size = size;
        this.semaphore = new Semaphore(size, true);
    }

    /**
     * The shared permits of {@code database}; the first caller's {@code size} sizes them, a later different one is
     * logged and ignored
     */
    static CatchUpPermits forDatabase(String database, int size) {
        var permits = REGISTRY.computeIfAbsent(database, key -> new CatchUpPermits(key, size));
        if (permits.size != size) {
            log.warn("Catch-up permits of database {} are already sized {} by another EventLog in this JVM; catchUpPermits {} is ignored", database, permits.size, size);
        }
        return permits;
    }

    /** A private set of permits, not shared: mutant {@link WatermarkMutant#PERMIT_PER_EVENT_LOG} */
    static CatchUpPermits unshared(String database, int size) {
        return new CatchUpPermits(database, size);
    }

    /**
     * The database's identity: {@code current_database()}, server address and port, or the JDBC URL's host part where
     * the address is null (a socket)
     */
    static String databaseKey(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.DATABASE_IDENTITY);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            var address = resultSet.getString(2);
            if (address == null) {
                var url = connection.getMetaData().getURL();
                address = url == null ? "local" : url.replaceFirst("^jdbc:postgresql://", "").replaceFirst("[/?].*$", "");
            }
            return resultSet.getString(1) + "@" + address + ":" + resultSet.getInt(3);
        }
    }

    /** @return the database key */
    String database() {
        return database;
    }

    /** @return how many permits there are */
    int size() {
        return size;
    }

    /** @return permits in use now */
    int inUse() {
        return size - semaphore.availablePermits();
    }

    /** Wait for a permit, fairly; interruptible. @return the nanoseconds waited */
    long acquire() throws InterruptedException {
        var started = System.nanoTime();
        semaphore.acquire();
        return System.nanoTime() - started;
    }

    void release() {
        semaphore.release();
    }
}
