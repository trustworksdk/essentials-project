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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.api;

import dk.trustworks.essentials.components.adminapi.rest.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.fencedlock.api.DBFencedLockApi;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi;
import dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi;
import dk.trustworks.essentials.components.foundation.scheduler.api.SchedulerApi;
import dk.trustworks.essentials.shared.security.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auto-configuration exposing the Essentials admin/monitoring SPIs over HTTP, conformant to the contract in
 * {@code admin-api-spec}.
 * <p>
 * <b>Security.</b> This module authenticates nobody and depends on no security framework. It resolves the caller's
 * principal through the application's {@link EssentialsAuthenticatedUser} implementation and lets the application's
 * {@link EssentialsSecurityProvider} implementation authorize each operation by role, inside the SPI beans. Both
 * default to their no-access implementations, so an application that has implemented neither rejects every request —
 * {@code 401} for the absent user, {@code 403} once a user exists but holds no roles. A prominent warning is logged
 * at startup while those defaults are in place, because the exposed operations include destructive ones (purge a
 * queue, delete a message, release a lock).
 *
 * @see EssentialsAdminApiProperties
 */
/*
 * Ordered after the auto-configurations that define the aggregate SPI beans. @ConditionalOnBean is evaluated against
 * the beans registered so far, so without this the aggregate controllers below are silently skipped whenever this
 * auto-configuration happens to be processed first — the endpoints then answer 404 with nothing in the logs to say why.
 * Named as strings because eventsourced-aggregates is an optional dependency here and the classes may be absent.
 */
@AutoConfiguration(afterName = {
        "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.AggregateLifecycleApiConfiguration",
        "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.AggregateArchiveApiConfiguration",
        "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.SnapshotConfiguration",
        "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.ClosingBooksConfiguration"})
@ConditionalOnClass(RestController.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "essentials.admin-api", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(EssentialsAdminApiProperties.class)
public class EssentialsAdminApiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EssentialsAdminApiAutoConfiguration.class);

    /**
     * Renders Essentials semantic value types as the JSON primitives the contract declares — without it a
     * {@code QueueName} would serialize as a nested object. Spring Boot registers every {@code JacksonModule} bean
     * into the mapper used for HTTP message conversion.
     */
    @Bean
    @ConditionalOnMissingBean
    public AdminApiJacksonModule essentialsAdminApiJacksonModule() {
        return new AdminApiJacksonModule();
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminApiPrincipalResolver essentialsAdminApiPrincipalResolver(EssentialsAuthenticatedUser authenticatedUser,
                                                                        EssentialsSecurityProvider securityProvider) {
        warnIfSecurityIsNotImplemented(authenticatedUser, securityProvider);
        return new AdminApiPrincipalResolver(authenticatedUser);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminApiExceptionHandler essentialsAdminApiExceptionHandler() {
        return new AdminApiExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public FencedLocksController essentialsFencedLocksController(DBFencedLockApi dbFencedLockApi,
                                                                 AdminApiPrincipalResolver principalResolver) {
        return new FencedLocksController(dbFencedLockApi, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchedulerController essentialsSchedulerController(SchedulerApi schedulerApi,
                                                             AdminApiPrincipalResolver principalResolver) {
        return new SchedulerController(schedulerApi, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public PostgresqlQueryStatisticsController essentialsPostgresqlQueryStatisticsController(PostgresqlQueryStatisticsApi postgresqlQueryStatisticsApi,
                                                                                            AdminApiPrincipalResolver principalResolver) {
        return new PostgresqlQueryStatisticsController(postgresqlQueryStatisticsApi, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableQueuesController essentialsDurableQueuesController(DurableQueuesApi durableQueuesApi,
                                                                     AdminApiPrincipalResolver principalResolver) {
        return new DurableQueuesController(durableQueuesApi, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStoreController essentialsEventStoreController(EventStoreApi eventStoreApi,
                                                               AdminApiPrincipalResolver principalResolver) {
        return new EventStoreController(eventStoreApi, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public CdcController essentialsCdcController(CdcApi cdcApi,
                                                 AdminApiPrincipalResolver principalResolver) {
        return new CdcController(cdcApi, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStoreStatisticsController essentialsEventStoreStatisticsController(PostgresqlEventStoreStatisticsApi statisticsApi,
                                                                                  AdminApiPrincipalResolver principalResolver) {
        return new EventStoreStatisticsController(statisticsApi, principalResolver);
    }

    /**
     * The admin API is only as safe as the two SPIs the consumer supplies. Running on the framework defaults is a
     * valid state — every request is simply rejected — but it is never what a deployment intends, so say so loudly
     * rather than leaving an operator to wonder why the API returns nothing but 401s and 403s.
     */
    private static void warnIfSecurityIsNotImplemented(EssentialsAuthenticatedUser authenticatedUser,
                                                       EssentialsSecurityProvider securityProvider) {
        var noUser     = authenticatedUser instanceof EssentialsAuthenticatedUser.NoAccessAuthenticatedUser;
        var noProvider = securityProvider instanceof EssentialsSecurityProvider.NoAccessSecurityProvider;
        if (noUser || noProvider) {
            log.warn("""
                     ### The Essentials admin HTTP API is exposed, but security is not implemented. \
                     Every request will be rejected (401/403) until the application provides its own \
                     EssentialsAuthenticatedUser{} and EssentialsSecurityProvider{} bean. ###""",
                     noUser ? " (currently NoAccessAuthenticatedUser)" : "",
                     noProvider ? " (currently NoAccessSecurityProvider)" : "");
        }
        if (securityProvider instanceof EssentialsSecurityProvider.AllAccessSecurityProvider) {
            log.warn("""
                     ### The Essentials admin HTTP API is exposed with AllAccessSecurityProvider: every caller is \
                     authorized for every operation, including destructive ones (purge queue, delete message, \
                     release lock). Do not run this in production. ###""");
        }
    }

    /**
     * The aggregate lifecycle and archive controllers, unlike the other seven, cannot be declared unconditionally.
     * {@code eventsourced-aggregates} is an optional dependency of an application using the admin API at all, and even
     * with it on the classpath the SPI beans are conditional: the archive ones only exist when
     * {@code essentials.eventstore.archives.enabled} is true. Each controller is therefore gated on its own SPI bean,
     * so enabling the admin API never fails a context for a subsystem the application does not run.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AggregateLifecycleApi.class)
    public static class AggregateAdminApiConfiguration {

        @Bean
        @ConditionalOnBean(AggregateLifecycleApi.class)
        @ConditionalOnMissingBean
        public AggregateLifecycleController essentialsAggregateLifecycleController(AggregateLifecycleApi aggregateLifecycleApi,
                                                                                  AdminApiPrincipalResolver principalResolver) {
            return new AggregateLifecycleController(aggregateLifecycleApi, principalResolver);
        }

        @Bean
        @ConditionalOnBean(AggregateLifecycleStatisticsApi.class)
        @ConditionalOnMissingBean
        public AggregateLifecycleStatisticsController essentialsAggregateLifecycleStatisticsController(AggregateLifecycleStatisticsApi aggregateLifecycleStatisticsApi,
                                                                                                      AdminApiPrincipalResolver principalResolver) {
            return new AggregateLifecycleStatisticsController(aggregateLifecycleStatisticsApi, principalResolver);
        }

        @Bean
        @ConditionalOnBean(AggregateArchiveApi.class)
        @ConditionalOnMissingBean
        public AggregateArchiveController essentialsAggregateArchiveController(AggregateArchiveApi aggregateArchiveApi,
                                                                              AdminApiPrincipalResolver principalResolver) {
            return new AggregateArchiveController(aggregateArchiveApi, principalResolver);
        }

        @Bean
        @ConditionalOnBean(AggregateArchiveStatisticsApi.class)
        @ConditionalOnMissingBean
        public AggregateArchiveStatisticsController essentialsAggregateArchiveStatisticsController(AggregateArchiveStatisticsApi aggregateArchiveStatisticsApi,
                                                                                                  AdminApiPrincipalResolver principalResolver) {
            return new AggregateArchiveStatisticsController(aggregateArchiveStatisticsApi, principalResolver);
        }
    }
}
