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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import dk.trustworks.essentials.components.boot.autoconfigure.admin.api.EssentialsAdminApiAutoConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.LockName;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import dk.trustworks.essentials.shared.security.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.*;
import org.springframework.context.annotation.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Runs the admin UI against stubbed SPIs so the real thing can be opened, driven and screenshotted —
 * templates, static assets, the API adapter and the JavaScript all exercised together.
 * <p>
 * There is no database: the seven {@code *Api} beans are Mockito stubs returning fixture data of the
 * shape the contract declares. That is enough, because the UI only ever talks HTTP to the API, and the
 * API only ever talks to these beans.
 * <p>
 * Used by {@code screenshots/capture.mjs}; also runnable by hand for eyeballing a change:
 * <pre>{@code mvn -pl components/spring-boot-starter-admin-ui test-compile exec:java \
 *   -Dexec.mainClass=dk.trustworks.essentials.components.boot.autoconfigure.admin.ui.AdminUiDemoApplication \
 *   -Dexec.classpathScope=test}</pre>
 */
@Configuration
@ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        ThymeleafAutoConfiguration.class,
        EssentialsAdminApiAutoConfiguration.class,
        EssentialsAdminUiAutoConfiguration.class
})
public class AdminUiDemoApplication {

    public static void main(String[] args) {
        var port = System.getProperty("demo.port", "8099");
        var app  = new SpringApplication(AdminUiDemoApplication.class);
        app.setDefaultProperties(Map.of("server.port", port));
        app.run(args);
        System.out.println("ADMIN_UI_READY http://localhost:" + port + "/essentials/admin");
    }

    /** Every role granted — the point is to exercise the UI, not the authorization path. */
    @Bean
    EssentialsAuthenticatedUser authenticatedUser() {
        return new EssentialsAuthenticatedUser.AllAccessAuthenticatedUser();
    }

    @Bean
    EssentialsSecurityProvider securityProvider() {
        return new EssentialsSecurityProvider.AllAccessSecurityProvider();
    }

    @Bean
    DBFencedLockApi dbFencedLockApi() {
        var api = mock(DBFencedLockApi.class);
        when(api.getAllLocks(any())).thenReturn(List.of(
                new ApiDBFencedLock(LockName.of("EventProcessor-OrderProcessor"), 42L, "orders-7c9f",
                                    OffsetDateTime.parse("2026-07-31T09:15:02Z"), OffsetDateTime.parse("2026-07-31T12:04:12Z")),
                new ApiDBFencedLock(LockName.of("TTLManager-eventstore_cdc_inbox"), 8L, "orders-3ab1",
                                    OffsetDateTime.parse("2026-07-30T22:00:00Z"), OffsetDateTime.parse("2026-07-31T12:03:58Z")),
                // An unheld lock — token and holder are optional in the contract.
                new ApiDBFencedLock(LockName.of("NightlyReconciliation"), null, null, null, null)));
        when(api.releaseLock(any(), any())).thenReturn(true);
        return api;
    }

    @Bean
    DurableQueuesApi durableQueuesApi() {
        var api = mock(DurableQueuesApi.class);
        when(api.getQueueNames(any())).thenReturn(new LinkedHashSet<>(List.of(
                QueueName.of("OrderEvents"), QueueName.of("ShipmentCommands"), QueueName.of("EmailOutbox"))));
        when(api.getTotalMessagesQueuedFor(any(), any())).thenReturn(128L);
        when(api.getTotalDeadLetterMessagesQueuedFor(any(), any())).thenReturn(3L);
        when(api.getQueuedMessages(any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of(
                queued("018f2c11-9a1e-7c3d-b0f1-2a5c9e11aa01", "{\"orderId\":\"ORD-99213\",\"total\":149.95}", 0, 0, false, null),
                queued("018f2c11-9a1e-7c3d-b0f1-2a5c9e11aa02", "{\"orderId\":\"ORD-99214\",\"total\":32.00}", 1, 0, true, null),
                // payload null: the caller lacks QUEUE_PAYLOAD_READER, which the contract documents
                queued("018f2c11-9a1e-7c3d-b0f1-2a5c9e11aa03", null, 2, 1, false, "Connection reset by peer")));
        when(api.getDeadLetterMessages(any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of(
                dead("018f2b04-51cc-7a10-9d22-71bb0e77dd10"), dead("018f2b04-51cc-7a10-9d22-71bb0e77dd11")));
        when(api.getQueuedMessage(any(), any())).thenReturn(Optional.of(
                queued("018f2c11-9a1e-7c3d-b0f1-2a5c9e11aa01", "{\"orderId\":\"ORD-99213\",\"total\":149.95}", 0, 0, false, null)));
        when(api.getQueueNameFor(any(), any())).thenReturn(Optional.of(QueueName.of("OrderEvents")));
        when(api.deleteMessage(any(), any())).thenReturn(true);
        when(api.purgeQueue(any(), any())).thenReturn(131);
        return api;
    }

    private static ApiQueuedMessage queued(String id, String payload, int attempts, int redeliveries,
                                           boolean delivering, String error) {
        return new ApiQueuedMessage(QueueEntryId.of(id), QueueName.of("OrderEvents"), payload,
                                    OffsetDateTime.parse("2026-07-31T12:04:20Z"),
                                    OffsetDateTime.parse("2026-07-31T12:04:35Z"), null, error,
                                    attempts, redeliveries, false, delivering, null, null, null);
    }

    private static ApiQueuedMessage dead(String id) {
        return new ApiQueuedMessage(QueueEntryId.of(id), QueueName.of("OrderEvents"),
                                    "{\"orderId\":\"ORD-98120\",\"customerId\":\"CUST-40219\",\"currency\":\"DKK\"}",
                                    OffsetDateTime.parse("2026-07-31T08:11:02Z"), null,
                                    OffsetDateTime.parse("2026-07-31T08:44:19Z"),
                                    "PaymentGatewayTimeoutException: no response after 30s\n\tat PaymentGatewayClient.authorize(PaymentGatewayClient.java:88)",
                                    5, 4, true, false,
                                    // An EventProcessor inbox message: it refers to the persisted event Payments/payment-1042#2
                                    "payment-1042", 2L, "Payments");
    }

    @Bean
    SchedulerApi schedulerApi() {
        var api = mock(SchedulerApi.class);
        when(api.getPgCronJobs(any(), anyLong(), anyLong())).thenReturn(List.of(
                new ApiPgCronJob(1, "*/5 * * * *", "CALL essentials_ttl_delete('eventstore_cdc_inbox')",
                                 "localhost", 5432, "orders", true, "ttl-eventstore_cdc_inbox"),
                new ApiPgCronJob(2, "0 4 * * 0", "VACUUM ANALYZE orders_events",
                                 "localhost", 5432, "orders", false, "weekly-vacuum")));
        when(api.getTotalPgCronJobs(any())).thenReturn(2L);
        when(api.getPgCronJobRunDetails(any(), any(), anyLong(), anyLong())).thenReturn(List.of(
                new ApiPgCronJobRunDetails(1, 8841, 21044, "orders", "essentials", "CALL essentials_ttl_delete(…)",
                                           "succeeded", null, LocalDateTime.parse("2026-07-31T12:00:00"), LocalDateTime.parse("2026-07-31T12:00:01")),
                new ApiPgCronJobRunDetails(1, 8839, 20903, "orders", "essentials", "CALL essentials_ttl_delete(…)",
                                           "failed", "canceling statement due to statement timeout",
                                           LocalDateTime.parse("2026-07-31T11:50:00"), LocalDateTime.parse("2026-07-31T11:50:31"))));
        when(api.getTotalPgCronJobRunDetails(any(), any())).thenReturn(2L);
        when(api.getExecutorJobs(any(), anyLong(), anyLong())).thenReturn(List.of(
                new ApiExecutorJob("CdcEffectivenessMonitor", 30, 60, TimeUnit.SECONDS, LocalDateTime.parse("2026-07-31T09:15:00")),
                new ApiExecutorJob("FencedLockConfirmation", 0, 5, TimeUnit.SECONDS, LocalDateTime.parse("2026-07-31T09:15:00"))));
        when(api.getTotalExecutorJobs(any())).thenReturn(2L);
        // An executor job that fails, so the console's failed-run notice can be seen; anything else succeeds
        when(api.runJobNow(any(), any())).thenAnswer(invocation -> {
            String jobName = invocation.getArgument(1);
            var failed = jobName.startsWith("FencedLockConfirmation");
            return Optional.of(new ApiScheduledJobRun(jobName, jobName.contains("Confirmation") || jobName.contains("Monitor") ? "EXECUTOR" : "PG_CRON",
                                                      OffsetDateTime.now(), failed ? 3 : 184, !failed,
                                                      failed ? "java.lang.IllegalStateException: lock table unreachable" : null));
        });
        return api;
    }

    @Bean
    PostgresqlQueryStatisticsApi postgresqlQueryStatisticsApi() {
        var api = mock(PostgresqlQueryStatisticsApi.class);
        var queries = List.of(
                new ApiQueryStatistics("SELECT * FROM shard_queue_unordered WHERE queue_id = $1 AND shard = $2 LIMIT $3",
                                       412044.10, 2940211, 0.14, 0, 0.02, 41.20, 0.31, 18820441, 0, 100.0),
                new ApiQueryStatistics("SELECT * FROM orders_events WHERE global_order > $1 ORDER BY global_order LIMIT $2",
                                       184203.44, 91204, 2.02, 4410221, 0.40, 912.04, 6.12, 9120442, 88120, 99.0),
                new ApiQueryStatistics("INSERT INTO orders_events (global_order, aggregate_id, …) VALUES ($1, $2, …)",
                                       92044.10, 918204, 0.10, 918204, 0.04, 88.10, 0.22, 4120441, 1022, 100.0),
                new ApiQueryStatistics("DELETE FROM eventstore_cdc_inbox WHERE received_at < $1",
                                       21044.55, 8841, 2.38, 812044, 0.88, 1204.55, 14.02, 220441, 61204, 78.3));
        when(api.getTopTenSlowestQueries(any())).thenReturn(queries);
        when(api.getSlowestQueries(any(), any(), anyInt())).thenAnswer(invocation -> {
            QueryStatisticsOrder orderBy = invocation.getArgument(1);
            ToDoubleFunction<ApiQueryStatistics> rank = switch (orderBy) {
                case TOTAL_TIME -> ApiQueryStatistics::totalTime;
                case MEAN_TIME -> ApiQueryStatistics::meanTime;
                case MAX_TIME -> ApiQueryStatistics::maxTime;
                case CALLS -> ApiQueryStatistics::calls;
                case BLOCKS_READ -> ApiQueryStatistics::sharedBlksRead;
            };
            return queries.stream()
                          .sorted(Comparator.comparingDouble(rank).reversed())
                          .limit(invocation.<Integer>getArgument(2))
                          .toList();
        });
        return api;
    }

    @Bean
    PostgresqlTableStatisticsApi postgresqlTableStatisticsApi() {
        var api = mock(PostgresqlTableStatisticsApi.class);
        when(api.fetchTableStatistics(any())).thenReturn(List.of(
                tableStatistics("event-store", "orders_events", 4_423_000_000L, 3_252_000_000L, 1_170_000_000L,
                                918204, 120, 12, 4210394, 918204, 0, 0, 99.4),
                tableStatistics("event-store", "payments_events", 512_000_000L, 401_000_000L, 111_000_000L,
                                45219, 0, 3, 210394, 45219, 0, 0, 97.1),
                tableStatistics("subscriptions", "durable_subscriptions", 98_304L, 8_192L, 32_768L,
                                12, 140, 2, 412044, 12, 918204, 0, 100.0),
                tableStatistics("subscriptions", "transient_subscriber_gaps", 49_152L, 8_192L, 16_384L,
                                3, 41, 0, 41204, 27, 0, 24, null),
                tableStatistics("cdc", "eventstore_cdc_inbox", 221_000_000L, 180_000_000L, 41_000_000L,
                                40210, 8841, 881, 120394, 963414, 920114, 923204, 71.2),
                tableStatistics("durable-queues", "durable_queues", 222_000_000L, 162_000_000L, 60_000_000L,
                                1204, 92044, 881, 9204113, 184331, 368662, 184203, 92.0),
                tableStatistics("fenced-locks", "fenced_locks", 65_536L, 8_192L, 16_384L,
                                6, 4, 0, 1844201, 6, 921004, 0, 100.0),
                tableStatistics("aggregates", "aggregate_snapshots", 12_000_000L, 9_000_000L, 3_000_000L,
                                1820, 12, 0, 41204, 1832, 0, 12, 98.8),
                tableStatistics("infrastructure", "essentials_schema_history", 49_152L, 8_192L, 16_384L,
                                31, 0, 2, 0, 31, 0, 0, 100.0)));
        return api;
    }

    private static ApiTableStatistics tableStatistics(String section, String table, long totalBytes, long tableBytes, long indexBytes,
                                                      long liveRows, long deadRows, long seqScan, long idxScan,
                                                      long inserted, long updated, long deleted, Double cacheHitRatio) {
        // Fixture shape: a primary key carrying most lookups, and on the busier tables a secondary index - unused on
        // the CDC inbox, so the console has something to flag. Updates are mostly HOT except on the queue table.
        var indexes = new ArrayList<ApiIndexStatistics>();
        indexes.add(new ApiIndexStatistics(table + "_pkey", indexBytes * 2 / 3, pretty(indexBytes * 2 / 3),
                                           idxScan, idxScan * 2, idxScan, true, true, true, cacheHitRatio));
        if (indexBytes > 1_000_000L) {
            var unused = table.equals("eventstore_cdc_inbox");
            indexes.add(new ApiIndexStatistics(table + (unused ? "_received_at_idx" : "_status_idx"), indexBytes / 3, pretty(indexBytes / 3),
                                               unused ? 0 : idxScan / 4, unused ? 0 : idxScan / 2, unused ? 0 : idxScan / 4,
                                               false, false, true, unused ? null : cacheHitRatio));
        }
        var hotUpdated = table.equals("durable_queues") ? updated / 5 : updated * 9 / 10;
        return new ApiTableStatistics(section, table, totalBytes, tableBytes, indexBytes,
                                      pretty(totalBytes), pretty(tableBytes), pretty(indexBytes),
                                      liveRows, deadRows, seqScan, seqScan * liveRows, idxScan, idxScan,
                                      inserted, updated, hotUpdated, deleted, cacheHitRatio,
                                      OffsetDateTime.parse("2026-07-31T11:02:14Z"), OffsetDateTime.parse("2026-07-31T11:02:15Z"),
                                      indexes);
    }

    private static String pretty(long bytes) {
        return bytes >= 1_048_576L ? bytes / 1_048_576 + " MB" : bytes / 1024 + " kB";
    }

    @Bean
    EventStoreApi eventStoreApi() {
        var orderProcessor    = dk.trustworks.essentials.components.foundation.types.SubscriberId.of("OrderProcessor");
        var paymentProjection = dk.trustworks.essentials.components.foundation.types.SubscriberId.of("PaymentProjection");
        var api               = mock(EventStoreApi.class);
        when(api.findAllSubscriptions(any())).thenReturn(List.of(
                new ApiSubscription(orderProcessor,
                                    AggregateType.of("Orders"), 918204L, OffsetDateTime.parse("2026-07-31T12:04:29Z"),
                                    true, true, true, true, false, null, 918211L, false),
                new ApiSubscription(paymentProjection,
                                    AggregateType.of("Payments"), 45219L, OffsetDateTime.parse("2026-07-31T12:04:22Z"),
                                    true, false, null, null, null, null, null, null),
                new ApiSubscription(dk.trustworks.essentials.components.foundation.types.SubscriberId.of("InvoiceProjection"),
                                    AggregateType.of("Orders"), 917640L, OffsetDateTime.parse("2026-07-31T11:58:13Z"),
                                    true, true, true, false, false, null, 917640L, true)));
        when(api.findHighestGlobalEventOrderPersisted(any(), any())).thenReturn(Optional.of(GlobalEventOrder.of(918204L)));
        var orderProcessorStatistics = new ApiSubscriptionStatistics(
                orderProcessor,
                AggregateType.of("Orders"),
                OffsetDateTime.parse("2026-07-31T09:12:04Z"),
                new ApiSubscriptionLifecycleStatistics(1, 0, OffsetDateTime.parse("2026-07-31T09:12:04Z"), null),
                new ApiSubscriptionEventHandlingStatistics(918211, 918204, 3,
                                                           OffsetDateTime.parse("2026-07-31T12:04:29Z"), 918204L,
                                                           4L, 812L,
                                                           OffsetDateTime.parse("2026-07-31T11:47:02Z"),
                                                           "OptimisticLockingException: Aggregate 'Order:42' was modified concurrently",
                                                           100),
                new ApiSubscriptionPollingStatistics(41204, 38911, 12044,
                                                     OffsetDateTime.parse("2026-07-31T12:04:29Z"), 2L, 4, 18),
                new ApiSubscriptionLockStatistics(1, 0, true, OffsetDateTime.parse("2026-07-31T09:12:05Z"), null),
                new ApiSubscriptionResetStatistics(0, null, null),
                new ApiSubscriptionGapStatistics(27, 24, 1,
                                                 OffsetDateTime.parse("2026-07-31T12:03:51Z"),
                                                 OffsetDateTime.parse("2026-07-31T10:40:12Z")));
        when(api.findAllSubscriptionStatistics(any())).thenReturn(List.of(orderProcessorStatistics));
        when(api.findSubscriptionStatistics(any(), any(), any())).thenReturn(Optional.of(orderProcessorStatistics));

        // A causation chain shaped like the webshop's capture flow: the order, the hold it led to, the capture request,
        // and the capture the gateway's webhook recorded. Start from 7c1e0f9a-0004-... to walk it back.
        var causationEvents = new LinkedHashMap<String, ApiCausationEvent>();
        for (var event : List.of(
                new ApiCausationEvent("7c1e0f9a-0001-4b8e-9c55-1f0e9c3d2b11", "Orders", "order-1042", "OrderPlaced", 2, 918190,
                                      OffsetDateTime.parse("2026-07-31T12:01:02Z"), null),
                new ApiCausationEvent("7c1e0f9a-0002-4b8e-9c55-1f0e9c3d2b11", "Payments", "payment-1042", "CreditCardHoldPlaced", 0, 45201,
                                      OffsetDateTime.parse("2026-07-31T12:01:03Z"), "7c1e0f9a-0001-4b8e-9c55-1f0e9c3d2b11"),
                new ApiCausationEvent("7c1e0f9a-0003-4b8e-9c55-1f0e9c3d2b11", "Payments", "payment-1042", "FundsCaptureRequested", 1, 45214,
                                      OffsetDateTime.parse("2026-07-31T12:03:40Z"), "7c1e0f9a-0002-4b8e-9c55-1f0e9c3d2b11"),
                new ApiCausationEvent("7c1e0f9a-0004-4b8e-9c55-1f0e9c3d2b11", "Payments", "payment-1042", "FundsCaptured", 2, 45219,
                                      OffsetDateTime.parse("2026-07-31T12:03:41Z"), "7c1e0f9a-0003-4b8e-9c55-1f0e9c3d2b11"))) {
            causationEvents.put(event.eventId(), event);
        }
        when(api.findEvent(any(), any())).thenAnswer(invocation -> Optional.ofNullable(causationEvents.get(invocation.getArgument(1).toString())));
        when(api.findCausationChain(any(), any(), anyInt())).thenAnswer(invocation -> {
            var chain = new ArrayList<ApiCausationEvent>();
            var next  = causationEvents.get(invocation.getArgument(1).toString());
            while (next != null && chain.size() < (int) invocation.getArgument(2)) {
                chain.add(next);
                next = next.causedByEventId() == null ? null : causationEvents.get(next.causedByEventId());
            }
            return chain;
        });
        when(api.findAggregateEvents(any(), any(), any(), anyInt())).thenAnswer(invocation -> causationEvents.values().stream()
                                                                                                    .filter(event -> event.aggregateType().equals(invocation.getArgument(1).toString())
                                                                                                                     && event.aggregateId().equals(invocation.getArgument(2)))
                                                                                                    .toList());
        when(api.findEventsCausedBy(any(), any())).thenAnswer(invocation -> causationEvents.values().stream()
                                                                                          .filter(event -> invocation.getArgument(1).toString().equals(event.causedByEventId()))
                                                                                          .toList());
        return api;
    }

    @Bean
    PostgresqlEventStoreStatisticsApi statisticsApi() {
        var api = mock(PostgresqlEventStoreStatisticsApi.class);
        when(api.fetchTableSizeStatistics(any())).thenReturn(new LinkedHashMap<>(Map.of(
                "orders_events", new ApiTableSizeStatistics("4218 MB", "3102 MB", "1116 MB"),
                "durable_queues", new ApiTableSizeStatistics("212 MB", "155 MB", "57 MB"))));
        when(api.fetchTableActivityStatistics(any())).thenReturn(new LinkedHashMap<>(Map.of(
                "orders_events", new ApiTableActivityStatistics(12, 918204, 4210394, 8402193, 918204, 0, 0),
                "durable_queues", new ApiTableActivityStatistics(881, 4102934, 9204113, 12402193, 184331, 368662, 184203))));
        when(api.fetchTableCacheHitRatio(any())).thenReturn(new LinkedHashMap<>(Map.of(
                "orders_events", new ApiTableCacheHitRatio(99),
                "eventstore_cdc_inbox", new ApiTableCacheHitRatio(71))));
        return api;
    }

    @Bean
    CdcApi cdcApi() {
        var api = mock(CdcApi.class);
        when(api.getStatus(any())).thenReturn(new ApiCdcStatus(
                // Healthy steady state: no fallbacks, and the two warm-up polls every startup produces while
                // subscriptions wait for the WAL tailer to connect.
                new ApiCdcAvailability("ACTIVE", "slot_orders_prod", null, 1785484502000L, 0, 2, true),
                new ApiCdcConfiguration(true, "AUTO", "pgoutput", "essentials_pub", "INBOX", "BYTES", 500,
                                        "eventstore_cdc_inbox", 3600L, "slot_orders_prod", "PERSISTENT", "orders",
                                        "PT0.05S", "PT0.2S", "PT2S", "PT10S", 200, "PT0.05S", "SKIP_AND_LOG", "DELETE"),
                new ApiCdcSlotStatus("slot_orders_prod", true, "PERSISTENT", "pgoutput", true, true, 21044,
                                     "logical", "pgoutput", "orders", false, "0/1955460", "0/1955AA8", "reserved",
                                     1073741824L, null, "f", null, false, false),
                // tailer null: only the instance holding the slot lock runs it
                null,
                new ApiCdcDispatcherStatus("slot_orders_prod", true, false, 88412, 0, 0, 2, 0, 918204, 37, 1785488668000L),
                // One recovered interruption: a dropped replication connection that reconnected on its own
                new ApiCdcInterruptions(1, false,
                                        OffsetDateTime.parse("2026-07-31T11:19:36Z"),
                                        "Database connection failed when writing to copy",
                                        OffsetDateTime.parse("2026-07-31T11:19:36.600Z"))));
        return api;
    }
}
