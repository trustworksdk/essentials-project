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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues.QueueingSortOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import io.swagger.v3.oas.models.media.*;

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

    /** The seven SPI interfaces the contract covers (parity-checked against the operations below). */
    static final List<Class<?>> API_INTERFACES = List.of(
            DBFencedLockApi.class,
            SchedulerApi.class,
            PostgresqlQueryStatisticsApi.class,
            DurableQueuesApi.class,
            EventStoreApi.class,
            CdcApi.class,
            PostgresqlEventStoreStatisticsApi.class);

    /** DTO record types reflected into {@code components.schemas} (nested types are resolved transitively). */
    static final List<Class<?>> DTO_CLASSES = List.of(
            ApiDBFencedLock.class,
            ApiPgCronJob.class,
            ApiPgCronJobRunDetails.class,
            ApiExecutorJob.class,
            ApiQueryStatistics.class,
            ApiTableSizeStatistics.class,
            ApiTableActivityStatistics.class,
            ApiTableCacheHitRatio.class,
            ApiQueuedMessage.class,
            ApiQueuedStatistics.class,
            ApiSubscription.class,
            ApiCdcStatus.class);

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
            "ApiQueuedStatistics", Set.of("queueName"),
            "ApiSubscription", Set.of("subscriberId", "aggregateType"),
            "ApiCdcStatus", Set.of("availability", "configuration", "slot"));

    /**
     * DTO properties that are {@code null} by design, with the reason surfaced as the property description.
     * Reflection cannot derive these: they are either role-gated redactions or components that are not running
     * in the queried instance.
     */
    static final Map<String, Map<String, String>> NULLABLE_PROPERTIES = Map.of(
            "ApiQueuedMessage", Map.of(
                    "payload", "The raw message payload. Null unless the caller holds the QUEUE_PAYLOAD_READER "
                            + "or ESSENTIALS_ADMIN role."),
            "ApiCdcStatus", Map.of(
                    "tailer", "Null when no WAL replication tailer is running in this instance.",
                    "dispatcher", "Null when no CDC dispatcher is running in this instance."));

    /** Tag name &rarr; description, in display order. */
    static final Map<String, String> TAGS = new LinkedHashMap<>() {{
        put("fenced-locks", "Inspect and release distributed fenced locks.");
        put("scheduler", "Inspect pg_cron jobs, their run history, and executor jobs.");
        put("postgresql-query-statistics", "Inspect slow-query statistics from pg_stat_statements.");
        put("durable-queues", "Inspect and manage durable queue and dead-letter messages.");
        put("event-store", "Inspect event-store subscriptions and persisted event order.");
        put("cdc", "Inspect Change Data Capture runtime state and effective configuration.");
        put("event-store-statistics", "Inspect event-store table size, activity, and cache-hit statistics.");
    }};

    // Wire role strings (kept in sync with EssentialsSecurityRoles).
    private static final String ADMIN          = ESSENTIALS_ADMIN.getRoleName();
    private static final String LOCK_R         = LOCK_READER.getRoleName();
    private static final String LOCK_W         = LOCK_WRITER.getRoleName();
    private static final String SCHEDULER_R    = SCHEDULER_READER.getRoleName();
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

        // ---- postgresql-query-statistics ----
        b.operation(PostgresqlQueryStatisticsApi.class, "getTopTenSlowestQueries")
         .tag("postgresql-query-statistics").get("/postgresql/query-statistics/top-ten-slowest")
         .summary("Return the ten slowest queries from pg_stat_statements.")
         .roles(STATS_R, ADMIN)
         .responseArray("ApiQueryStatistics");

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

        b.operation(DurableQueuesApi.class, "purgeQueue")
         .tag("durable-queues").delete("/durable-queues/queues/{queueName}/messages")
         .summary("Purge all messages (including dead-letters) from a queue.")
         .roles(QUEUE_W, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responsePurged();

        b.operation(DurableQueuesApi.class, "getQueuedStatistics")
         .tag("durable-queues").get("/durable-queues/queues/{queueName}/statistics")
         .summary("Get delivery statistics for a queue.")
         .roles(QUEUE_R, ADMIN)
         .pathParam("queueName", new StringSchema(), "The queue name.")
         .responseOptionalRef("ApiQueuedStatistics", "The queue statistics.");

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
