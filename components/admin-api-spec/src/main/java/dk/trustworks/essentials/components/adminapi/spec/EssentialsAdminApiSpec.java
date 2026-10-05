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

package dk.trustworks.essentials.components.adminapi.spec;

import dk.trustworks.essentials.components.adminapi.spec.OpenApiSpecGenerator.SpecBuilder;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.queue.shardowned.api.*;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues.QueueingSortOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import io.swagger.v3.oas.models.media.*;

import java.math.BigDecimal;
import java.util.*;

import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;

/**
 * Declarative, code-first mapping of the Essentials admin {@code *Api} SPI interfaces onto the HTTP contract.
 * <p>
 * This is the low-churn half of the contract: the REST shape (verb, path, parameters, required roles) for every
 * method of every {@link #API_INTERFACES interface}. The high-churn half — the JSON schemas — is reflected
 * automatically from {@link #DTO_CLASSES} by {@link OpenApiSpecGenerator}. {@link OpenApiSpecGenerator} also
 * verifies that every declared interface method is mapped here exactly once.
 */
final class EssentialsAdminApiSpec {

    /** Major-versioned base path; a breaking change introduces {@code .../v2} served side-by-side. */
    static final String BASE_PATH        = "/api/essentials/admin/v1";
    /** Semantic contract version; the major aligns with the {@link #BASE_PATH} major. */
    static final String CONTRACT_VERSION = "1.0.0";

    private EssentialsAdminApiSpec() {
    }

    /** The SPI interfaces the contract covers (parity-checked against the operations below). */
    static final List<Class<?>> API_INTERFACES = List.of(
            DBFencedLockApi.class,
            SchedulerApi.class,
            PostgresqlQueryStatisticsApi.class,
            PostgresqlTableStatisticsApi.class,
            DurableQueuesApi.class,
            EventStoreApi.class,
            CdcApi.class,
            PostgresqlEventStoreStatisticsApi.class,
            AggregateLifecycleApi.class,
            AggregateLifecycleStatisticsApi.class,
            AggregateArchiveApi.class,
            AggregateArchiveStatisticsApi.class,
            ShardOwnedQueuesApi.class);

    /** DTO record types reflected into {@code components.schemas} (nested types are resolved transitively). */
    static final List<Class<?>> DTO_CLASSES = List.of(
            ApiDBFencedLock.class,
            ApiPgCronJob.class,
            ApiPgCronJobRunDetails.class,
            ApiExecutorJob.class,
            ApiScheduledJobRun.class,
            ApiQueryStatistics.class,
            ApiTableSizeStatistics.class,
            ApiTableActivityStatistics.class,
            ApiTableCacheHitRatio.class,
            ApiTableStatistics.class,
            ApiIndexStatistics.class,
            ApiQueuedMessage.class,
            ApiShardOwnedMessage.class,
            ApiShardOwnedQueueStatus.class,
            ApiShardOwnedQueueStatistics.class,
            ApiQueueStatistics.class,
            ApiSubscription.class,
            ApiSubscriptionStatistics.class,
            ApiCausationEvent.class,
            ApiCdcStatus.class,
            ApiAggregateSnapshotPolicy.class,
            ApiAggregateClosingBooksPolicy.class,
            ApiClosingBooksGeneration.class,
            ApiClosingBooksGenerationEventStream.class,
            ApiAggregateSnapshot.class,
            ApiAggregateSnapshotStatistics.class,
            ApiAggregateClosingBooksStatistics.class,
            ApiArchivedGeneration.class,
            ApiAggregateArchiveStatistics.class);

    /**
     * Reference-typed DTO properties that are verified to always be present, and are therefore marked
     * {@code required} in the contract.
     * <p>
     * Primitive-typed record components are marked required automatically — the type system guarantees a value.
     * Every other component stays optional unless listed here, so a generated client is never told a field is
     * guaranteed when the server can legitimately send {@code null}.
     */
    static final Map<String, Set<String>> ALWAYS_PRESENT_PROPERTIES = Map.of(
            "ApiDBFencedLock", Set.of("lockName"),
            "ApiQueuedMessage", Set.of("id", "queueName"),
            "ApiQueueStatistics", Set.of("queueName", "depth"),
            "ApiSubscription", Set.of("subscriberId", "aggregateType"),
            "ApiSubscriptionStatistics", Set.of("subscriberId", "aggregateType", "statisticsSince",
                                                "lifecycle", "eventHandling", "polling", "lock", "reset"),
            "ApiCdcStatus", Set.of("availability", "configuration", "slot"),
            "ApiTableStatistics", Set.of("section", "tableName", "totalSize", "tableSize", "indexSize", "indexes"),
            "ApiIndexStatistics", Set.of("indexName", "size"),
            "ApiScheduledJobRun", Set.of("jobName", "jobType", "startedAt"),
            "ApiCausationEvent", Set.of("eventId", "aggregateType", "aggregateId", "eventType", "timestamp"));

    /**
     * DTO properties that are {@code null} by design, with the reason surfaced as the property description.
     * Reflection cannot derive these: they are either role-gated redactions or components that are not running
     * in the queried instance.
     */
    static final Map<String, Map<String, String>> NULLABLE_PROPERTIES = Map.of(
            "ApiQueryStatistics", Map.of(
                    "cacheHitRatio", "Shared-buffer hits as a percentage 0-100 of all shared blocks the statement "
                            + "accessed. Null when it accessed no shared blocks."),
            "ApiTableStatistics", Map.of(
                    "cacheHitRatio", "Shared-buffer hits as a percentage 0-100 of the table's and its indexes' block "
                            + "requests. Null while there has been no block access since the statistics were reset.",
                    "lastVacuum", "The most recent manual or automatic vacuum. Null if the table was never vacuumed.",
                    "lastAnalyze", "The most recent manual or automatic analyze. Null if the table was never analyzed."),
            "ApiScheduledJobRun", Map.of(
                    "error", "The exception type and message. Null when the run succeeded."),
            "ApiIndexStatistics", Map.of(
                    "cacheHitRatio", "Shared-buffer hits as a percentage 0-100 of the index's block requests. Null while "
                            + "there has been no block access since the statistics were reset."),
            "ApiQueuedMessage", Map.of(
                    "payload", "The raw message payload. Null unless the caller holds the QUEUE_PAYLOAD_READER "
                            + "or ESSENTIALS_ADMIN role.",
                    "orderedMessageKey", "The key of an ordered message. Null for an unordered message.",
                    "orderedMessageOrder", "The order of an ordered message. Null for an unordered message.",
                    "referencedAggregateType", "Set when the message refers to a persisted event as the inbox messages "
                            + "of an EventProcessor do. It is the aggregate type and orderedMessageKey is the aggregate id. "
                            + "Null for every other message."),
            "ApiCdcStatus", Map.of(
                    "tailer", "Null when no WAL replication tailer is running in this instance.",
                    "dispatcher", "Null when no CDC dispatcher is running in this instance."),
            "ApiSubscription", Map.of(
                    "lastUpdated", "When the durable resume point was last updated. Null when the subscription has no "
                            + "durable resume point - see durableResumePointPresent.",
                    "active", "Whether the subscription is currently active. Null when the subscription is not running "
                            + "in this instance.",
                    "exclusive", "Whether only one instance at a time may run this subscription. Null when the "
                            + "subscription is not running in this instance.",
                    "inTransaction", "Whether events are handled in the transaction that persisted them. Null when the "
                            + "subscription is not running in this instance.",
                    "tenant", "The tenant the subscription is restricted to. Null when the subscription is not "
                            + "restricted to a tenant or is not running in this instance.",
                    "inMemoryGlobalOrder", "The in-memory resume point of the running subscription. It can be ahead of "
                            + "currentGlobalOrder. Null when the subscription is not running in this instance.",
                    "stoppedByErrorPolicy", "Whether the error policy of the subscription halted it after a handler "
                            + "failure (mode STOP). It then handles no further events until it is started again. active stays true for "
                            + "such a subscription. Always false for an in-transaction subscription. Null when the "
                            + "subscription is not running in this instance."),
            "ApiCausationEvent", Map.of(
                    "causedByEventId", "The id of the event that caused this one. Null when no cause was recorded. Events "
                            + "started by a request or a person have none. Neither do events persisted before causation "
                            + "was recorded."));

    /** Tag name &rarr; description, in display order. */
    static final Map<String, String> TAGS = new LinkedHashMap<>() {{
        put("fenced-locks", "Inspect and release distributed fenced locks.");
        put("scheduler", "Inspect pg_cron jobs, their run history, and executor jobs, and run a job on demand.");
        put("postgresql-query-statistics", "Inspect slow-query statistics from pg_stat_statements.");
        put("postgresql-table-statistics", "Inspect size, activity, and cache-hit statistics for every table the Essentials components own.");
        put("durable-queues", "Inspect and manage durable queue and dead-letter messages.");
        put("event-store", "Inspect event-store subscriptions and persisted event order, and walk event causation.");
        put("cdc", "Inspect Change Data Capture runtime state and effective configuration.");
        put("event-store-statistics", "Inspect event-store table size, activity, and cache-hit statistics.");
        put("aggregate-lifecycle", "Inspect aggregate snapshot and closing-books policies, generations, and snapshots.");
        put("aggregate-lifecycle-statistics", "Inspect aggregate snapshot and closing-books runtime statistics.");
        put("aggregate-archive", "Inspect archived closing-books generations.");
        put("aggregate-archive-statistics", "Inspect aggregate archive runtime statistics.");
    }};

    // Wire role strings (kept in sync with EssentialsSecurityRoles).
    private static final String ADMIN          = ESSENTIALS_ADMIN.getRoleName();
    private static final String LOCK_R         = LOCK_READER.getRoleName();
    private static final String LOCK_W         = LOCK_WRITER.getRoleName();
    private static final String SCHEDULER_R    = SCHEDULER_READER.getRoleName();
    private static final String SCHEDULER_W    = SCHEDULER_WRITER.getRoleName();
    private static final String STATS_R        = POSTGRESQL_STATS_READER.getRoleName();
    private static final String QUEUE_R        = QUEUE_READER.getRoleName();
    private static final String QUEUE_W        = QUEUE_WRITER.getRoleName();
    private static final String SUBSCRIPTION_R = SUBSCRIPTION_READER.getRoleName();

    /** Registers all operations. Adding/removing an interface method without updating this triggers a build failure. */
    static void defineOperations(SpecBuilder b) {
        // ---- fenced-locks ----
        b.operation(DBFencedLockApi.class, "getAllLocks")
         .tag("fenced-locks").get("/fenced-locks")
         .summary("List all database-backed fenced locks currently present in the system.")
         .roles(LOCK_R, ADMIN)
         .responseArray("ApiDBFencedLock");

        b.operation(DBFencedLockApi.class, "releaseLock")
         .tag("fenced-locks").delete("/fenced-locks/{lockName}")
         .summary("Release the fenced lock with the given name.")
         .roles(LOCK_W, ADMIN)
         .pathParam("lockName", new StringSchema(), "Name of the lock to release.")
         .responseReleased();

        // ---- scheduler ----
        b.operation(SchedulerApi.class, "getPgCronJobs")
         .tag("scheduler").get("/scheduler/pg-cron-jobs")
         .summary("List PostgreSQL pg_cron jobs (paginated).")
         .roles(SCHEDULER_R, ADMIN).pagination()
         .responseArray("ApiPgCronJob");

        b.operation(SchedulerApi.class, "getTotalPgCronJobs")
         .tag("scheduler").get("/scheduler/pg-cron-jobs/count")
         .summary("Count PostgreSQL pg_cron jobs.")
         .roles(SCHEDULER_R, ADMIN)
         .responseCount();

        b.operation(SchedulerApi.class, "getPgCronJobRunDetails")
         .tag("scheduler").get("/scheduler/pg-cron-jobs/{jobId}/run-details")
         .summary("List execution details for a pg_cron job (paginated).")
         .roles(SCHEDULER_R, ADMIN)
         .pathParam("jobId", new IntegerSchema().format("int32"), "The pg_cron job id.")
         .pagination()
         .responseArray("ApiPgCronJobRunDetails");

        b.operation(SchedulerApi.class, "getTotalPgCronJobRunDetails")
         .tag("scheduler").get("/scheduler/pg-cron-jobs/{jobId}/run-details/count")
         .summary("Count execution details for a pg_cron job.")
         .roles(SCHEDULER_R, ADMIN)
         .pathParam("jobId", new IntegerSchema().format("int32"), "The pg_cron job id.")
         .responseCount();

        b.operation(SchedulerApi.class, "getExecutorJobs")
         .tag("scheduler").get("/scheduler/executor-jobs")
         .summary("List API executor jobs (paginated).")
         .roles(SCHEDULER_R, ADMIN).pagination()
         .responseArray("ApiExecutorJob");

        b.operation(SchedulerApi.class, "getTotalExecutorJobs")
         .tag("scheduler").get("/scheduler/executor-jobs/count")
         .summary("Count API executor jobs.")
         .roles(SCHEDULER_R, ADMIN)
         .responseCount();

        b.operation(SchedulerApi.class, "runJobNow")
         .tag("scheduler").post("/scheduler/jobs/{jobName}/run")
         .summary("Run a job registered with the scheduler once, now, and return the outcome. Its schedule is not changed. "
                  + "A pg_cron job's run is not recorded in pg_cron's run history; a manual run is not coordinated with "
                  + "a scheduled run of the same job.")
         .roles(SCHEDULER_W, ADMIN)
         .pathParam("jobName", new StringSchema(), "The job name, as listed by the executor or pg_cron job operations.")
         .conflict("An executor job runs only on the instance holding the scheduler lock, and this request reached another "
                   + "instance. The message names the instance holding the lock, when one does.")
         .responseOptionalRef("ApiScheduledJobRun", "The outcome of the run.");

        // ---- postgresql-query-statistics ----
        b.operation(PostgresqlQueryStatisticsApi.class, "getTopTenSlowestQueries")
         .tag("postgresql-query-statistics").get("/postgresql/query-statistics/top-ten-slowest")
         .summary("Return the ten slowest queries from pg_stat_statements.")
         .roles(STATS_R, ADMIN)
         .responseArray("ApiQueryStatistics");

        b.operation(PostgresqlQueryStatisticsApi.class, "getSlowestQueries")
         .tag("postgresql-query-statistics").get("/postgresql/query-statistics/slowest")
         .summary("Return the statements recorded by pg_stat_statements for the current database, ranked by the chosen order.")
         .roles(STATS_R, ADMIN)
         .queryParam("orderBy", queryStatisticsOrderSchema(), false,
                     "What to rank by. TOTAL_TIME favours cheap statements that run constantly; MEAN_TIME and MAX_TIME "
                             + "surface statements that are slow per call.")
         .queryParam("limit", new IntegerSchema()._default(10).minimum(BigDecimal.ONE)
                                                 .maximum(BigDecimal.valueOf(PostgresqlQueryStatisticsApi.MAX_SLOWEST_QUERIES_LIMIT)), false,
                     "Maximum number of statements to return. A value above the maximum is capped.")
         .responseArray("ApiQueryStatistics");

        // ---- postgresql-table-statistics ----
        b.operation(PostgresqlTableStatisticsApi.class, "fetchTableStatistics")
         .tag("postgresql-table-statistics").get("/postgresql/table-statistics")
         .summary("Return size, activity, and cache-hit statistics per Essentials table, grouped by section.")
         .roles(STATS_R, ADMIN)
         .responseArray("ApiTableStatistics");

        // ---- durable-queues ----
        b.operation(DurableQueuesApi.class, "getQueueNames")
         .tag("durable-queues").get("/durable-queues")
         .summary("List the names of all accessible durable queues.")
         .roles(QUEUE_R, ADMIN)
         .responseStringSet("The accessible queue names.");

        b.operation(DurableQueuesApi.class, "getQueuedMessage")
         .tag("durable-queues").get("/durable-queues/messages/{queueEntryId}")
         .summary("Get a single queued message by its entry id.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueEntryId", new StringSchema(), "The queue entry id.")
         .responseOptionalRef("ApiQueuedMessage", "The queued message.");

        b.operation(DurableQueuesApi.class, "getQueueNameFor")
         .tag("durable-queues").get("/durable-queues/messages/{queueEntryId}/queue-name")
         .summary("Resolve the queue name owning a given message entry id.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueEntryId", new StringSchema(), "The queue entry id.")
         .responseQueueNameOptional();

        b.operation(DurableQueuesApi.class, "resurrectDeadLetterMessage")
         .tag("durable-queues").post("/durable-queues/messages/{queueEntryId}/resurrect")
         .summary("Resurrect a dead-letter message, re-queuing it after an optional delay.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueEntryId", new StringSchema(), "The dead-letter message entry id.")
         .requestBody("ResurrectDeadLetterMessageRequest")
         .responseOptionalRef("ApiQueuedMessage", "The resurrected message.");

        b.operation(DurableQueuesApi.class, "markAsDeadLetterMessage")
         .tag("durable-queues").post("/durable-queues/messages/{queueEntryId}/mark-as-dead-letter")
         .summary("Mark a queued message as a dead-letter message.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueEntryId", new StringSchema(), "The queue entry id.")
         .responseOptionalRef("ApiQueuedMessage", "The updated message.");

        b.operation(DurableQueuesApi.class, "deleteMessage")
         .tag("durable-queues").delete("/durable-queues/messages/{queueEntryId}")
         .summary("Delete a message from its queue.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueEntryId", new StringSchema(), "The queue entry id.")
         .responseDeleted();

        b.operation(DurableQueuesApi.class, "getTotalMessagesQueuedFor")
         .tag("durable-queues").get("/durable-queues/queues/{queueName}/messages/count")
         .summary("Count messages queued for a queue.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseCount();

        b.operation(DurableQueuesApi.class, "getTotalDeadLetterMessagesQueuedFor")
         .tag("durable-queues").get("/durable-queues/queues/{queueName}/dead-letter-messages/count")
         .summary("Count dead-letter messages queued for a queue.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseCount();

        b.operation(DurableQueuesApi.class, "getQueuedMessages")
         .tag("durable-queues").get("/durable-queues/queues/{queueName}/messages")
         .summary("List queued messages for a queue (paginated, sortable).")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .queryParam("sortOrder", sortOrderSchema(), false, "Sort order by queue entry id.")
         .pagination()
         .responseArray("ApiQueuedMessage");

        b.operation(DurableQueuesApi.class, "getDeadLetterMessages")
         .tag("durable-queues").get("/durable-queues/queues/{queueName}/dead-letter-messages")
         .summary("List dead-letter messages for a queue (paginated, sortable).")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .queryParam("sortOrder", sortOrderSchema(), false, "Sort order by queue entry id.")
         .pagination()
         .responseArray("ApiQueuedMessage");

        b.operation(DurableQueuesApi.class, "getQueueStatistics")
         .tag("durable-queues").get("/durable-queues/queues/{queueName}/statistics")
         .summary("Get cluster-wide depth and this instance's delivery statistics for a queue.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseRef("ApiQueueStatistics", "The queue statistics.");

        b.operation(DurableQueuesApi.class, "purgeQueue")
         .tag("durable-queues").delete("/durable-queues/queues/{queueName}/messages")
         .summary("Purge all messages (including dead-letters) from a queue.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responsePurged();

        // ---- shard-owned-queues ----
        // Paths mirror the controller in spring-boot-starter-postgresql-queue-shard-owned. Every one
        // is queue-scoped: a MessageId is (lane, shard, sequence) and unique per queue only, so the
        // queue name is part of addressing a message rather than a convenience.
        b.operation(ShardOwnedQueuesApi.class, "getQueueNames")
         .operationId("shardOwnedGetQueueNames")
         .tag("shard-owned-queues").get("/shard-owned-queues")
         .summary("List the names of all accessible shard-owned queues.")
         .roles(QUEUE_R, ADMIN)
         .responseStringSet("The accessible queue names.");

        b.operation(ShardOwnedQueuesApi.class, "getQueueStatus")
         .operationId("shardOwnedGetQueueStatus")
         .tag("shard-owned-queues").get("/shard-owned-queues/{queueName}/status")
         .summary("Depth and ownership for a queue, per lane.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseOptionalRef("ApiShardOwnedQueueStatus", "The queue's depth and ownership.");

        b.operation(ShardOwnedQueuesApi.class, "getQueueStatistics")
         .operationId("shardOwnedGetQueueStatistics")
         .tag("shard-owned-queues").get("/shard-owned-queues/{queueName}/statistics")
         .summary("Delivery counters for a queue, as recorded by the instance answering the request.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseRef("ApiShardOwnedQueueStatistics", "This instance's counters for the queue.");

        b.operation(ShardOwnedQueuesApi.class, "getMessage")
         .operationId("shardOwnedGetMessage")
         .tag("shard-owned-queues").get("/shard-owned-queues/{queueName}/messages/{messageId}")
         .summary("Get a single message by its id within a queue.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .pathParam("messageId", new StringSchema(), "The message id, as lane-shard-sequence (for example u-3-1042).")
         .responseOptionalRef("ApiShardOwnedMessage", "The message.");

        b.operation(ShardOwnedQueuesApi.class, "getDeadLetterMessages")
         .operationId("shardOwnedGetDeadLetterMessages")
         .tag("shard-owned-queues").get("/shard-owned-queues/{queueName}/dead-letter-messages")
         .summary("Page the dead letters of a queue.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .queryParam("offset", new IntegerSchema()._default(0), false, "Rows to skip.")
         .queryParam("limit", new IntegerSchema()._default(100), false, "Maximum rows to return.")
         .responseArray("ApiShardOwnedMessage");

        b.operation(ShardOwnedQueuesApi.class, "deleteMessage")
         .operationId("shardOwnedDeleteMessage")
         .tag("shard-owned-queues").delete("/shard-owned-queues/{queueName}/messages/{messageId}")
         .summary("Delete a message from its queue.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .pathParam("messageId", new StringSchema(), "The message id.")
         .responseDeleted();

        b.operation(ShardOwnedQueuesApi.class, "retryMessage")
         .operationId("shardOwnedRetryMessage")
         .tag("shard-owned-queues").post("/shard-owned-queues/{queueName}/messages/{messageId}/retry")
         .summary("Make a message visible again after an optional delay.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .pathParam("messageId", new StringSchema(), "The message id.")
         .responseMessageOperation();

        b.operation(ShardOwnedQueuesApi.class, "markAsDeadLetterMessage")
         .operationId("shardOwnedMarkAsDeadLetterMessage")
         .tag("shard-owned-queues").post("/shard-owned-queues/{queueName}/messages/{messageId}/mark-as-dead-letter")
         .summary("Park a message as a dead letter.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .pathParam("messageId", new StringSchema(), "The message id.")
         .responseMessageOperation();

        b.operation(ShardOwnedQueuesApi.class, "resurrectDeadLetterMessage")
         .operationId("shardOwnedResurrectDeadLetterMessage")
         .tag("shard-owned-queues").post("/shard-owned-queues/{queueName}/messages/{messageId}/resurrect")
         .summary("Return a dead letter to its lane. It re-enters at a fresh sequence, so its id changes.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .pathParam("messageId", new StringSchema(), "The dead-letter message id.")
         .responseMessageOperation();

        b.operation(ShardOwnedQueuesApi.class, "resurrectDeadLettersForKey")
         .operationId("shardOwnedResurrectDeadLettersForKey")
         .tag("shard-owned-queues").post("/shard-owned-queues/{queueName}/ordered-keys/{key}/resurrect")
         .summary("Return every dead letter of one ordered key to its lane, in key_order. The recovery "
                  + "operation for a key stopped behind a dead letter.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .pathParam("key", new StringSchema(), "The ordering key.")
         .responseRef("ShardOwnedResurrectKeyResult", "How many messages were put back.");

        b.operation(ShardOwnedQueuesApi.class, "purgeQueue")
         .operationId("shardOwnedPurgeQueue")
         .tag("shard-owned-queues").delete("/shard-owned-queues/{queueName}/messages")
         .summary("Delete every message in a queue, both lanes.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseShardOwnedPurge();

        // ---- event-store ----
        b.operation(EventStoreApi.class, "findHighestGlobalEventOrderPersisted")
         .tag("event-store").get("/event-store/aggregate-types/{aggregateType}/highest-global-event-order")
         .summary("Return the highest persisted global event order for an aggregate type.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .responseGlobalEventOrderOptional();

        b.operation(EventStoreApi.class, "findAllSubscriptions")
         .tag("event-store").get("/event-store/subscriptions")
         .summary("List all active event-store subscriptions.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiSubscription");

        b.operation(EventStoreApi.class, "findAllSubscriptionStatistics")
         .tag("event-store").get("/event-store/subscriptions/statistics")
         .summary("List runtime statistics for every event-store subscription running in this instance.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiSubscriptionStatistics");

        b.operation(EventStoreApi.class, "findEvent")
         .tag("event-store").get("/event-store/events/{eventId}")
         .summary("Find an event by its id alone, in whichever registered aggregate type's event stream holds it. "
                  + "Returns the event's identity and recorded cause, not its payload.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("eventId", new StringSchema(), "The event id.")
         .responseOptionalRef("ApiCausationEvent", "The event.");

        b.operation(EventStoreApi.class, "findAggregateEvents")
         .tag("event-store").get("/event-store/aggregate-types/{aggregateType}/aggregates/{aggregateId}/events")
         .summary("The most recent events of one aggregate, oldest first - the starting point for walking causation. "
                  + "Returns each event's identity and recorded cause, not its payload. Empty when the aggregate type is "
                  + "not registered or the aggregate has no events.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("aggregateId", new StringSchema(), "The aggregate id, as text.")
         .queryParam("limit", new IntegerSchema().format("int32").minimum(java.math.BigDecimal.ONE)
                                                  .maximum(java.math.BigDecimal.valueOf(EventStoreApi.MAX_AGGREGATE_EVENTS))
                                                  ._default(100),
                     false, "How many of the most recent events to return.")
         .responseArray("ApiCausationEvent");

        b.operation(EventStoreApi.class, "findCausationChain")
         .tag("event-store").get("/event-store/events/{eventId}/causation-chain")
         .summary("Why did this event happen: the event, then the event that caused it, then that event's cause, and so "
                  + "on. Stops at an event without a recorded cause, at a cause no registered event stream holds, or "
                  + "after maxDepth events.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("eventId", new StringSchema(), "The id of the event to start from.")
         .queryParam("maxDepth", new IntegerSchema().format("int32").minimum(java.math.BigDecimal.ONE)
                                                     .maximum(java.math.BigDecimal.valueOf(EventStoreApi.MAX_CAUSATION_CHAIN_DEPTH))
                                                     ._default(20),
                     false, "The most events to return, starting with the event itself.")
         .responseArray("ApiCausationEvent");

        b.operation(EventStoreApi.class, "findEventsCausedBy")
         .tag("event-store").get("/event-store/events/{eventId}/caused-events")
         .summary("What did this event cause: every event whose recorded cause is this event, across all registered "
                  + "aggregate types. Direct effects only. Requires the opt-in caused-by-event-id index.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("eventId", new StringSchema(), "The id of the causing event.")
         .conflict("The caused-by-event-id index is not enabled (essentials.eventstore.causation.index-enabled), and the "
                   + "lookup refuses to scan every event-stream table without it.")
         .responseArray("ApiCausationEvent");

        b.operation(EventStoreApi.class, "findSubscriptionStatistics")
         .tag("event-store").get("/event-store/subscriptions/{subscriberId}/aggregate-types/{aggregateType}/statistics")
         .summary("Get runtime statistics for one event-store subscription running in this instance.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("subscriberId", new StringSchema(), "The subscriber id.")
         .pathParam("aggregateType", new StringSchema(), "The aggregate type the subscriber subscribes to.")
         .responseOptionalRef("ApiSubscriptionStatistics", "The subscription statistics.");

        // ---- cdc ----
        b.operation(CdcApi.class, "getStatus")
         .tag("cdc").get("/event-store/cdc/status")
         .summary("Return a snapshot of CDC operational state and effective configuration.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseRef("ApiCdcStatus", "The CDC status snapshot.");

        // ---- event-store-statistics ----
        b.operation(PostgresqlEventStoreStatisticsApi.class, "fetchTableSizeStatistics")
         .tag("event-store-statistics").get("/event-store/statistics/table-sizes")
         .summary("Return size statistics per event-store table.")
         .roles(STATS_R, ADMIN)
         .responseMap("ApiTableSizeStatistics", "Table name to size statistics.");

        b.operation(PostgresqlEventStoreStatisticsApi.class, "fetchTableActivityStatistics")
         .tag("event-store-statistics").get("/event-store/statistics/table-activity")
         .summary("Return activity statistics per event-store table.")
         .roles(STATS_R, ADMIN)
         .responseMap("ApiTableActivityStatistics", "Table name to activity statistics.");

        b.operation(PostgresqlEventStoreStatisticsApi.class, "fetchTableCacheHitRatio")
         .tag("event-store-statistics").get("/event-store/statistics/table-cache-hit-ratio")
         .summary("Return cache-hit ratio per event-store table.")
         .roles(STATS_R, ADMIN)
         .responseMap("ApiTableCacheHitRatio", "Table name to cache-hit ratio.");

        // ---- aggregate-lifecycle ----
        b.operation(AggregateLifecycleApi.class, "findAllAggregateSnapshotPolicies")
         .tag("aggregate-lifecycle").get("/aggregate-lifecycle/snapshot-policies")
         .summary("List the aggregate snapshot policies registered in this instance.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiAggregateSnapshotPolicy");

        b.operation(AggregateLifecycleApi.class, "findAllAggregateClosingBooksPolicies")
         .tag("aggregate-lifecycle").get("/aggregate-lifecycle/closing-books-policies")
         .summary("List the aggregate closing-books policies registered in this instance.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiAggregateClosingBooksPolicy");

        b.operation(AggregateLifecycleApi.class, "findClosingBooksGenerations")
         .tag("aggregate-lifecycle").get("/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations")
         .summary("List all closing-books generations for a logical aggregate, oldest first.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("logicalAggregateId", new StringSchema(), "The logical aggregate id, i.e. the id spanning all generations.")
         .responseArray("ApiClosingBooksGeneration");

        b.operation(AggregateLifecycleApi.class, "findCurrentClosingBooksGeneration")
         .tag("aggregate-lifecycle").get("/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/current")
         .summary("Return the currently open closing-books generation for a logical aggregate.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("logicalAggregateId", new StringSchema(), "The logical aggregate id, i.e. the id spanning all generations.")
         .responseOptionalRef("ApiClosingBooksGeneration", "The currently open generation.");

        b.operation(AggregateLifecycleApi.class, "findClosingBooksGenerationEventStream")
         .tag("aggregate-lifecycle").get("/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/{generation}/event-stream")
         .summary("Return the persisted event stream of one closing-books generation.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("logicalAggregateId", new StringSchema(), "The logical aggregate id, i.e. the id spanning all generations.")
         .pathParam("generation", new IntegerSchema().format("int64"), "The generation number.")
         .responseOptionalRef("ApiClosingBooksGenerationEventStream", "The event stream of the generation.");

        b.operation(AggregateLifecycleApi.class, "findSnapshots")
         .tag("aggregate-lifecycle").get("/aggregate-lifecycle/aggregate-types/{aggregateType}/aggregates/{aggregateId}/snapshots")
         .summary("List the stored snapshots of an aggregate instance, oldest first.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("aggregateId", new StringSchema(), "The aggregate instance id.")
         .queryParam("includeSnapshotPayload", new BooleanSchema()._default(false), false,
                     "Include the serialized snapshot payload. Off by default, since payloads can be large.")
         .responseArray("ApiAggregateSnapshot");

        // ---- aggregate-lifecycle-statistics ----
        b.operation(AggregateLifecycleStatisticsApi.class, "findAggregateSnapshotStatistics")
         .tag("aggregate-lifecycle-statistics").get("/aggregate-lifecycle-statistics/snapshots")
         .summary("Return aggregate snapshot statistics per aggregate type.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiAggregateSnapshotStatistics");

        b.operation(AggregateLifecycleStatisticsApi.class, "findAggregateClosingBooksStatistics")
         .tag("aggregate-lifecycle-statistics").get("/aggregate-lifecycle-statistics/closing-books")
         .summary("Return aggregate closing-books statistics per aggregate type.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiAggregateClosingBooksStatistics");

        // ---- aggregate-archive ----
        b.operation(AggregateArchiveApi.class, "findArchivedGenerations")
         .tag("aggregate-archive").get("/aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations")
         .summary("List the archived generations of a logical aggregate.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("logicalAggregateId", new StringSchema(), "The logical aggregate id, i.e. the id spanning all generations.")
         .responseArray("ApiArchivedGeneration");

        b.operation(AggregateArchiveApi.class, "findArchivedGeneration")
         .tag("aggregate-archive").get("/aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations/{generation}")
         .summary("Return the archive entry of one generation of a logical aggregate.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .pathParam("aggregateType", new StringSchema(), "The aggregate type.")
         .pathParam("logicalAggregateId", new StringSchema(), "The logical aggregate id, i.e. the id spanning all generations.")
         .pathParam("generation", new IntegerSchema().format("int64"), "The generation number.")
         .responseOptionalRef("ApiArchivedGeneration", "The archive entry of the generation.");

        // ---- aggregate-archive-statistics ----
        b.operation(AggregateArchiveStatisticsApi.class, "findAggregateArchiveStatistics")
         .tag("aggregate-archive-statistics").get("/aggregate-archive-statistics")
         .summary("Return aggregate archive statistics per aggregate type.")
         .roles(SUBSCRIPTION_R, ADMIN)
         .responseArray("ApiAggregateArchiveStatistics");
    }

    private static StringSchema queryStatisticsOrderSchema() {
        var schema = new StringSchema();
        for (QueryStatisticsOrder value : QueryStatisticsOrder.values()) {
            schema.addEnumItem(value.name());
        }
        schema._default(QueryStatisticsOrder.TOTAL_TIME.name());
        return schema;
    }

    private static StringSchema sortOrderSchema() {
        var schema = new StringSchema();
        for (QueueingSortOrder value : QueueingSortOrder.values()) {
            schema.addEnumItem(value.name());
        }
        schema._default(QueueingSortOrder.ASC.name());
        return schema;
    }
}
