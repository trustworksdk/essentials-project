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

package dk.trustworks.essentials.components.foundation.scheduler.executor;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.scheduler.JobNameResolver;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.slf4j.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.components.foundation.scheduler.JobNameResolver.UNDER_SCORE;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

/**
 * A repository for managing scheduled executor jobs in a relational database. This class provides methods
 * to create, read, update, and delete job entries stored in a database table. Each job entry contains details
 * such as the job's name, initial delay, execution period, time unit, and the timestamp of when it was scheduled.
 * <p>
 * The table can either use a default name or a custom {@code sharedTableName} provided at instantiation, and the table structure
 * is validated or created if it does not exist.
 * <p>
 * If a custom {@code sharedTableName} is provided then it is validated using {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} as an initial layer
 * of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
 * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
 * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
 * <br>
 * It is highly recommended that the {@code sharedTableName} value is only derived from a controlled and trusted source.
 */
public class ExecutorScheduledJobRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutorScheduledJobRepository.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        sharedTableName;

    public static final String DEFAULT_SCHEDULED_JOBS_TABLE_NAME = "essentials_scheduled_executor_jobs";

    /**
     * Constructs an instance of {@link ExecutorScheduledJobRepository} using the provided {@link HandleAwareUnitOfWorkFactory}
     * and the default name for the shared database table to manage scheduled job entries.
     * <p>
     * This constructor delegates to the primary constructor, passing the {@code unitOfWorkFactory} along with
     * the default shared table name {@code DEFAULT_SCHEDULED_JOBS_TABLE_NAME}.
     * The constructed repository initializes the table upon creation if it does not already exist.
     * </p>
     *
     * @param unitOfWorkFactory a {@link HandleAwareUnitOfWorkFactory} that provides {@link HandleAwareUnitOfWork}
     *                          instances for managing database operations; must not be {@code null}.
     * @throws IllegalArgumentException if {@code unitOfWorkFactory} is {@code null}.
     */
    public ExecutorScheduledJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory, DEFAULT_SCHEDULED_JOBS_TABLE_NAME);
    }

    /**
     * Constructs an instance of {@link ExecutorScheduledJobRepository} that manages the persistence of
     * scheduled job entries in a shared database table. The table is initialized upon creation of this repository
     * if it does not already exist.
     *
     * @param unitOfWorkFactory a {@link HandleAwareUnitOfWorkFactory} that provides {@link HandleAwareUnitOfWork}
     *                          instances for managing database operations; must not be {@code null}.
     * @param sharedTableName   the name of the shared database table to store the scheduled jobs; must not be {@code null}
     *                          The {@code sharedTableName} is validated using {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)}  as an initial layer
     *                          of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
     *                          However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     *                          <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     *                          Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *                          Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     *                          <br>
     *                          It is highly recommended that the {@code sharedTableName} value is only derived from a controlled and trusted source.
     * @throws IllegalArgumentException if {@code unitOfWorkFactory} or {@code sharedTableName} is {@code null} or if {@code sharedTableName} is invalid according to database naming rules.
     */
    public ExecutorScheduledJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                          String sharedTableName) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.sharedTableName = requireNonNull(sharedTableName, "sharedTableName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedTableName);
        initializeTable();
    }

    /**
     * Initializes a database table to store scheduled job information if it does not already exist.
     * This method creates the table with predefined columns and ensures the presence of a unique index
     * on the `name` column for optimized lookup.
     * <p>
     * The table includes the following columns:
     * <ul>
     * <li>{@code name}: the primary key (TEXT).</li>
     * <li>{@code initial_delay}: the delay before the first execution (BIGINT, not null).</li>
     * <li>{@code period}: interval between executions (BIGINT, not null).</li>
     * <li>{@code time_unit}: the time unit of the delay and period (TEXT, not null).</li>
     * <li>{@code scheduled_at}: when the job was initially added to the repository (TIMESTAMPTZ, not null).</li>
     * </ul>
     */
    private void initializeTable() {
        var sql = bind("""
                       CREATE TABLE IF NOT EXISTS {:tableName} (
                       name          TEXT PRIMARY KEY,
                       initial_delay BIGINT NOT NULL,
                       period        BIGINT NOT NULL,
                       time_unit     TEXT NOT NULL,
                       scheduled_at  TIMESTAMPTZ NOT NULL
                       )
                       """, arg("tableName", sharedTableName));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(sql);
        });
    }

    /**
     * Inserts a new job entry into the database for scheduling purposes. The entry
     * is uniquely identified by a concatenation of the job's name and the instance identifier.
     *
     * @param job the job to be inserted into the scheduling repository; must not be null.
     *            The job's details, including its name, initial delay, period, and time unit,
     *            are used to populate the database entry.
     */
    public void insert(ExecutorJob job) {
        requireNonNull(job, "job cannot be null");
        var name = JobNameResolver.resolve(job.name());
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().createUpdate(bind(""" 
                                                   INSERT INTO {:tableName}
                                                           (name, initial_delay, period, time_unit, scheduled_at)
                                                           VALUES (:name, :initial_delay, :period, :time_unit, :scheduled_at)
                                           """, arg("tableName", sharedTableName)))
               .bind("name", name)
               .bind("initial_delay", job.fixedDelay().initialDelay())
               .bind("period", job.fixedDelay().period())
               .bind("time_unit", job.fixedDelay().unit().name())
               .bind("scheduled_at", OffsetDateTime.now())
               .execute();
        });
    }

    /**
     * Checks the existence of an entry in the database table with the specified name
     * concatenated with the current instance's identifier.
     *
     * @param name the base name of the entry to check for existence; must not be null.
     * @return true if an entry with the given name concatenated with the instance identifier exists; false otherwise.
     */
    public boolean existsByName(String name) {
        requireNonNull(name, "name cannot be null");
        var jobName = JobNameResolver.resolve(name);
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind(
                    """
                    SELECT EXISTS (SELECT 1 FROM {:tableName} WHERE name = :name)
                    """,
                    arg("tableName", sharedTableName));
            return uow.handle().createQuery(sql).bind("name", jobName)
                      .mapTo(Boolean.class)
                      .findOne()
                      .orElse(Boolean.FALSE);
        });
    }

    /**
     * Deletes an entry from the database table based on the specified name.
     *
     * @param name the name of the entry to delete; it corresponds to the `name` column in the database table.
     * @return {@code true} if the entry was successfully deleted (i.e., at least one row was affected),
     * {@code false} if no entry with the specified name exists in the database.
     */
    public boolean deleteByName(String name) {
        requireNonNull(name, "name cannot be null");
        var jobName = JobNameResolver.resolve(name);
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind(
                    """
                    DELETE FROM {:tableName} WHERE name = :name
                    """,
                    arg("tableName", sharedTableName));
            var affectedRows = uow.handle().createUpdate(sql)
                                  .bind("name", jobName)
                                  .execute();
            return affectedRows != 0;
        });
    }

    public void deleteByNameEndingWithInstanceId(String instanceId) {
        requireNonNull(instanceId, "instanceId cannot be null");
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var sql = bind(
                    """
                    DELETE FROM {:tableName} WHERE name LIKE '%' || :instanceid
                    """,
                    arg("tableName", sharedTableName));
            var affectedRows = uow.handle().createUpdate(sql)
                                  .bind("instanceid", UNDER_SCORE + instanceId)
                                  .execute();
            log.debug("Deleted '{}' scheduled job entries ending with instance id '{}'", affectedRows, instanceId);
        });
    }

    /**
     * Retrieves a list of scheduled job entries from the database with pagination and sorting options.
     *
     * @param limit  the maximum number of entries to retrieve
     * @param offset the starting position of the first entry to retrieve
     * @param asc    a flag indicating whether the results should be sorted in ascending order (true) or descending order (false)
     * @return a list of {@code ScheduledJobEntry} objects representing the scheduled job entries
     */
    public List<ExecutorJobEntry> fetchExecutorJobEntries(long limit, long offset, boolean asc) {
        var direction = asc ? "ASC" : "DESC";
        var sql = bind("""
                       SELECT name, initial_delay, period, time_unit, scheduled_at
                                                   FROM {:tableName}
                                                   ORDER BY scheduled_at {:direction}
                                                   LIMIT :limit OFFSET :offset
                       """, arg("tableName", sharedTableName),
                       arg("direction", direction));
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery(sql)
                                                          .bind("limit", limit)
                                                          .bind("offset", offset)
                                                          .map((rs, ctx) -> {
                                                              var name    = rs.getString("name");
                                                              var initial = rs.getLong("initial_delay");
                                                              var period  = rs.getLong("period");
                                                              var unit    = TimeUnit.valueOf(rs.getString("time_unit"));
                                                              var at      = rs.getObject("scheduled_at", OffsetDateTime.class);
                                                              return new ExecutorJobEntry(name, initial, period, unit, at);
                                                          })
                                                          .list());
    }

    /**
     * Retrieves the total number of scheduled job entries in the database.
     *
     * @return the total count of scheduled job entries as a {@code long}.
     */
    public long getTotalExecutorJobEntries() {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind("""
                           SELECT COUNT(*) FROM {:tableName}""",
                           arg("tableName", sharedTableName));
            return uow.handle().createQuery(sql)
                      .mapTo(Long.class)
                      .one();
        });
    }

    /**
     * Deletes all entries from the database table specified by the {@code sharedTableName} field.
     */
    public void deleteAll() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(bind("DELETE FROM {:tableName}", arg("tableName", sharedTableName)));
        });
    }

    /**
     * Represents an entry for a scheduled job in a database or scheduling system.
     * This record encapsulates all the required information for a scheduled job,
     * including its name, initial delay, period, time unit, and the timestamp
     * when it was initially scheduled.
     *
     * @param name         the unique name of the scheduled job
     * @param initialDelay the delay before the scheduled job starts executing for the first time
     * @param period       the interval between subsequent executions of the scheduled job
     * @param unit         the time unit of the initial delay and period
     * @param scheduledAt  the timestamp indicating when the job was initially scheduled
     */
    public record ExecutorJobEntry(
            String name,
            long initialDelay,
            long period,
            TimeUnit unit,
            OffsetDateTime scheduledAt
    ) {
    }

}
