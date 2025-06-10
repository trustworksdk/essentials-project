package dk.trustworks.essentials.components.foundation.scheduler.executor;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.jdbi.v3.core.statement.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public class ExecutorScheduledJobRepository {

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        sharedTableName;

    public static final String DEFAULT_SCHEDULED_JOBS_TABLE_NAME = "essentials_scheduled_executor_jobs";

    public ExecutorScheduledJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory, DEFAULT_SCHEDULED_JOBS_TABLE_NAME);
    }

    public ExecutorScheduledJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                         String sharedTableName) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.sharedTableName = sharedTableName;
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedTableName);
        initializeTable();
    }

    /**
     * Initializes a database table to store scheduled job information if it does not already exist.
     * This method creates the table with predefined columns and ensures the presence of a unique index
     * on the `name` column for optimized lookup.
     * <p>
     * The table includes the following columns:
     * - `name`: the primary key (TEXT).
     * - `initial_delay`: the delay before the first execution (BIGINT, not null).
     * - `period`: interval between executions (BIGINT, not null).
     * - `time_unit`: the time unit of the delay and period (TEXT, not null).
     * - `scheduled_at`: the initial scheduling timestamp (TIMESTAMPTZ, not null).
     * <p>
     * Additionally, an index is created on the `name` column to optimize query performance.
     */
    private void initializeTable() {
        String sql = bind("""
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
            uow.handle().execute(bind("CREATE INDEX IF NOT EXISTS idx_{:tableName}_name ON {:tableName} (name)", arg("tableName", sharedTableName)));
        });
    }

    /**
     * Inserts a new scheduled job into the database.
     *
     * @param job the scheduled job to be inserted. Contains the job's name, its fixed delay configuration,
     *            and other parameters such as initial delay, execution period, and time unit.
     */
    public void insert(ExecutorJob job) {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            Update u = uow.handle().createUpdate(bind(""" 
                        INSERT INTO {:tableName}
                                (name, initial_delay, period, time_unit, scheduled_at)
                                VALUES (:name, :initial_delay, :period, :time_unit, :scheduled_at)
                """, arg("tableName", sharedTableName)));
            u.bind("name", job.name())
             .bind("initial_delay", job.fixedDelay().initialDelay())
             .bind("period", job.fixedDelay().period())
             .bind("time_unit", job.fixedDelay().unit().name())
             .bind("scheduled_at", OffsetDateTime.now())
             .execute();
        });
    }

    /**
     * Checks if an entry with the given name exists in the database.
     *
     * @param name the name to check for existence in the database. It should match the `name` column in the table.
     * @return {@code true} if an entry with the specified name exists, {@code false} otherwise.
     */
    public boolean existsByName(String name) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind(
                    """
                            SELECT EXISTS (SELECT 1 FROM {:tableName} WHERE name = :name)
                            """,
                    arg("tableName", sharedTableName));
            Query q = uow.handle().createQuery(sql);
            return q.bind("name", name)
                    .mapTo(Boolean.class)
                    .findOne()
                    .orElse(Boolean.FALSE);
        });
    }

    /**
     * Retrieves a list of scheduled job entries from the database with pagination and sorting options.
     *
     * @param limit the maximum number of entries to retrieve
     * @param offset the starting position of the first entry to retrieve
     * @param asc a flag indicating whether the results should be sorted in ascending order (true) or descending order (false)
     * @return a list of {@code ScheduledJobEntry} objects representing the scheduled job entries
     */
    public List<ExecutorJobEntry> fetchExecutorJobEntries(long limit, long offset, boolean asc) {
        String direction = asc ? "ASC" : "DESC";
        String sql = bind("""
                            SELECT name, initial_delay, period, time_unit, scheduled_at
                                                        FROM {:tableName}
                                                        ORDER BY {:direction}
                                                        LIMIT :limit OFFSET :offset
                            """, arg("tableName", sharedTableName),
                          arg("direction", direction));
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            return uow.handle()
                      .createQuery(sql)
                      .bind("limit", limit)
                      .bind("offset", offset)
                      .map((rs, ctx) -> {
                          String name = rs.getString("name");
                          long initial = rs.getLong("initial_delay");
                          long           period = rs.getLong("period");
                          TimeUnit       unit   = TimeUnit.valueOf(rs.getString("time_unit"));
                          OffsetDateTime at     = rs.getObject("scheduled_at", OffsetDateTime.class);
                          return new ExecutorJobEntry(name, initial, period, unit, at);
                      })
                      .list();
        });
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
    public void delete() {
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
    public record ExecutorJobEntry(String name, long initialDelay, long period, TimeUnit unit, OffsetDateTime scheduledAt) {}

}
