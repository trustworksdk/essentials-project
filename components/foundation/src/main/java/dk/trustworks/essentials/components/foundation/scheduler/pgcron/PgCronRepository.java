/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.slf4j.*;

import java.time.OffsetDateTime;
import java.util.List;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

/**
 * Repository class for managing PostgreSQL cron jobs and their associated run details.
 * This class provides methods for scheduling, unscheduling, and retrieving cron job
 * information and run details.
 */
public class PgCronRepository {

    private static final Logger log = LoggerFactory.getLogger(PgCronRepository.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    private static final String PG_CRON_JOB_TABLE_NAME = "cron.job";
    private static final String PG_CRON_JOB_DETAILS_TABLE_NAME = "cron.job_run_details";

    /**
     * Constructs a new instance of {@code PgCronRepository} with the given unit of work factory.
     *
     * @param unitOfWorkFactory the {@link HandleAwareUnitOfWorkFactory} responsible for creating
     *                          and maintaining units of work in a handle-aware context
     */
    public PgCronRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    /**
     * Schedules a PostgreSQL cron job using the pg_cron extension. The method constructs
     * a SQL query based on the job details, executes it, and retrieves the job identifier
     * for the scheduled job.
     *
     * @param job the {@link PgCronJob} instance containing the cron expression and function
     *            to be scheduled
     * @return the identifier of the scheduled job as an {@link Integer}, or {@code null}
     *         if the scheduling fails or no ID is returned
     */
    public Integer schedule(PgCronJob job) {
        log.debug("Schedule PgCronJob '{}'", job);
        String sql = bind("SELECT cron.schedule('{:cron}', 'SELECT {:fn};');",
                          arg("cron", job.cronExpression()),
                          arg("fn", job.function()));
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            return uow.handle().createQuery(sql)
                      .mapTo(Integer.class)
                      .findFirst()
                      .orElse(null);
        });
    }

    /**
     * Unschedules a PostgreSQL cron job using the pg_cron extension. This method removes
     * the scheduled job from the database based on the job identifier provided.
     *
     * @param jobId the identifier of the pg_cron job to be unscheduled
     */
    public void unschedule(Integer jobId) {
        log.debug("Unscheduling PgCronJob with id '{}'", jobId);
        String sql = bind("SELECT cron.unschedule({:jobId});", arg("jobId", jobId));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(sql);
            log.debug("Unscheduled PgCronJob with id '{}'", jobId);
        });
    }

    public boolean doesJobExist(PgCronJob job) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind("""
                                        SELECT 1 FROM {:tableName} WHERE schedule = :cronExpression and command = :function"""
                    , arg("tableName", PG_CRON_JOB_TABLE_NAME));
            return uow.handle().createQuery(sql)
                      .bind("cronExpression", job.cronExpression())
                      .bind("function", job.function())
                      .mapTo(Boolean.class)
                      .findOne()
                      .orElse(Boolean.FALSE);
        });
    }

    /**
     * Retrieves a paginated list of PostgreSQL cron entries from the database.
     * This method queries the {@code PG_CRON_JOB_TABLE_NAME} table using the
     * provided start index and page size to limit and offset the results.
     *
     * @param startIndex the starting index for pagination, specifying the offset at which to begin retrieving entries
     * @param pageSize   the maximum number of entries to retrieve in the result set
     * @return a list of {@link PgCronEntry} representing the retrieved PostgreSQL cron entries
     */
    public List<PgCronEntry> fetchPgCronEntries(long startIndex, long pageSize) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind("""
                                        SELECT * FROM {:tableName} LIMIT :limit OFFSET :offset""",
                           arg("tableName", PG_CRON_JOB_TABLE_NAME));
            return uow.handle().createQuery(sql)
                      .bind("limit", pageSize)
                      .bind("offset", startIndex)
                      .map((rs, ctx) -> {
                          return new PgCronEntry(
                                  rs.getInt("jobid"),
                                  rs.getString("schedule"),
                                  rs.getString("command"),
                                  rs.getString("nodename"),
                                  rs.getInt("nodeport"),
                                  rs.getString("database"),
                                  rs.getBoolean("active"),
                                  rs.getString("jobname")
                          );
                      })
                      .collectIntoList();
        });
    }

    /**
     * Retrieves the total number of entries in the PostgreSQL cron job table.
     *
     * @return the count of entries in the {@code PG_CRON_JOB_TABLE_NAME} table as a {@code long}
     */
    public long getTotalPgCronEntries() {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind("""
                                        SELECT COUNT(*) FROM {:tableName}
                                        """,
                           arg("tableName", PG_CRON_JOB_TABLE_NAME));
            return uow.handle().createQuery(sql)
                      .mapTo(Long.class)
                      .one();
        });
    }

    /**
     * Fetches a paginated list of PostgreSQL cron job run details from the database.
     * This method retrieves job run information for a specific job ID within the range
     * specified by the start index and page size.
     *
     * @param jobId      the identifier of the cron job for which run details are to be fetched
     * @param startIndex the starting index for pagination, specifying the offset at which to begin retrieving job run details
     * @param pageSize   the maximum number of job run details to retrieve in the result set
     * @return a list of {@link PgCronJobRunDetails} containing the requested cron job run details
     */
    public List<PgCronJobRunDetails> fetchPgCronJobDetails(Integer jobId, long startIndex, long pageSize) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind("""
                                        SELECT * FROM {:tableName} WHERE jobid = :jobId LIMIT :limit OFFSET :offset""",
                           arg("tableName", PG_CRON_JOB_DETAILS_TABLE_NAME));
            return uow.handle().createQuery(sql)
                      .bind("jobId", jobId)
                      .bind("limit", pageSize)
                      .bind("offset", startIndex)
                      .map((rs, ctx) -> {
                          return new PgCronJobRunDetails(
                                  rs.getInt("jobid"),
                                  rs.getInt("runid"),
                                  rs.getInt("job_pid"),
                                  rs.getString("database"),
                                  rs.getString("username"),
                                  rs.getString("command"),
                                  rs.getString("status"),
                                  rs.getString("return_message"),
                                  rs.getObject("start_time", OffsetDateTime.class),
                                  rs.getObject("end_time", OffsetDateTime.class)
                          );
                      })
                      .collectIntoList();
        });
    }

    /**
     * Retrieves the total count of cron job details associated with a specific job ID.
     *
     * @param jobId the identifier of the PostgreSQL cron job for which the count of details is to be retrieved
     * @return the total number of job details for the specified job ID as a {@code long}
     */
    public long getTotalPgCronJobDetails(Integer jobId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var sql = bind("""
                                        SELECT COUNT(*) FROM {:tableName} WHERE jobid = :jobid""",
                           arg("tableName", PG_CRON_JOB_DETAILS_TABLE_NAME));
            return uow.handle().createQuery(sql)
                      .mapTo(Long.class)
                      .one();
        });
    }

    /**
     * Represents a single entry in the PostgreSQL cron job table, scheduled using the pg_cron extension.
     * This immutable record encapsulates the details of a scheduled job, including its schedule,
     * the command to be executed, associated node information, database, status, and job name.
     *
     * @param jobId       the unique identifier assigned to the scheduled cron job
     * @param schedule    the cron expression defining the execution schedule for the job
     * @param command     the command or function to be executed as part of the cron job
     * @param nodeName    the name of the node on which the job is scheduled to run, if applicable
     * @param nodePort    the port number of the node associated with the job
     * @param database    the database context in which the cron job is executed
     * @param active      a flag indicating whether the cron job is currently active
     * @param jobName     the name of the cron job, used for identification and organization
     */
    public record PgCronEntry(Integer jobId, String schedule, String command, String nodeName, Integer nodePort, String database, boolean active, String jobName) {}

    /**
     * Represents the details of a PostgreSQL cron job run as retrieved from the pg_cron extension.
     * This record is primarily used for storing and transferring information regarding individual
     * executions of a PostgreSQL cron job, including metadata such as job identifiers, execution timings,
     * and execution status.
     *
     * @param jobId         the identifier of the cron job
     * @param runId         the unique identifier of the job's execution instance
     * @param jobPid        the process ID of the job during execution
     * @param database      the name of the database in which the job was executed
     * @param username      the username under which the cron job was executed
     * @param command       the command executed as part of the cron job
     * @param status        the execution status of the job (e.g., 'success' or 'failure')
     * @param returnMessage a message or output returned by the job execution
     * @param startTime     the timestamp indicating when the job execution started
     * @param endTime       the timestamp indicating when the job execution ended
     */
    public record PgCronJobRunDetails(Integer jobId, Integer runId, Integer jobPid, String database, String username, String command, String status, String returnMessage, OffsetDateTime startTime, OffsetDateTime endTime) {}
}
