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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.adminapi.rest.AdminApiPrincipalResolver;
import dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue;
import dk.trustworks.essentials.components.queue.shardowned.api.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administrative surface, wired only when the admin API starter is on the classpath.
 *
 * <h2>Why this is conditional rather than always on</h2>
 * {@code spring-boot-starter-admin-api} brings the event-store starter with it. An application
 * that wants a queue and nothing else must not acquire an event store by depending on this
 * starter, so the dependency is {@code provided} and everything here is guarded by the presence
 * of the classes it needs. An application that already serves the admin API gets these endpoints
 * by adding no configuration at all.
 *
 * <h2>Why the controller is here and not in the admin API starter</h2>
 * It was here because the engine was unpublished, and a controller in a published artifact cannot
 * depend on one that reaches no repository. That reason is gone — the engine is published and its
 * operations are declared in {@code EssentialsAdminApiSpec}, so these endpoints do appear in the
 * generated OpenAPI document. What keeps the controller in this starter now is narrower: an
 * application that does not use this engine should not carry its controller, and the conditions
 * below are what express that. Moving it across would make the admin API starter depend on the
 * engine unconditionally.
 *
 * <h2>Why this is a separate auto-configuration</h2>
 * Its beans are declared {@code @ConditionalOnBean}, and that condition is evaluated in
 * auto-configuration order: the beans it looks for must already be defined or it silently backs off
 * and the endpoints 404 with nothing logged. It therefore has to be ordered <em>after</em>
 * {@code EssentialsAdminApiAutoConfiguration}.
 * <p>
 * {@link ShardOwnedQueueAutoConfiguration} cannot carry it, because that one is ordered
 * <em>before</em> {@code EssentialsComponentsConfiguration} so its {@code DurableQueues} bean can
 * displace the default — and the admin API auto-configuration is itself after
 * {@code EssentialsComponentsConfiguration}, so one class cannot satisfy both. Merging them is what
 * broke these endpoints once already.
 */
@AutoConfiguration(afterName = {
        "dk.trustworks.essentials.components.boot.autoconfigure.admin.api.EssentialsAdminApiAutoConfiguration",
        "dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.ShardOwnedQueueAutoConfiguration"})
@ConditionalOnClass({ShardOwnedQueue.class, RestController.class, AdminApiPrincipalResolver.class})
@ConditionalOnProperty(prefix = "essentials.shard-owned-queue", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class ShardOwnedQueuesAdminApiAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedQueuesAdminApiAutoConfiguration.class);


    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EssentialsSecurityProvider.class)
    public ShardOwnedQueuesApi shardOwnedQueuesApi(EssentialsSecurityProvider securityProvider,
                                                   ShardOwnedQueueFactory factory) {
        return new DefaultShardOwnedQueuesApi(securityProvider, factory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ShardOwnedQueuesApi.class, AdminApiPrincipalResolver.class})
    public ShardOwnedQueuesController shardOwnedQueuesController(ShardOwnedQueuesApi shardOwnedQueuesApi,
                                                                 AdminApiPrincipalResolver principalResolver) {
        log.info("Shard-owned queue admin endpoints are served under the Essentials admin API base path");
        return new ShardOwnedQueuesController(shardOwnedQueuesApi, principalResolver);
    }

    /**
     * Without this the admin API's error mapping does not reach this starter's controller, because
     * that advice is package-scoped to the admin API starter. A 404 then arrives as a 500, which the
     * console reads as a failure rather than as "already delivered".
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AdminApiPrincipalResolver.class)
    public ShardOwnedAdminApiExceptionHandler shardOwnedAdminApiExceptionHandler() {
        return new ShardOwnedAdminApiExceptionHandler();
    }

    /**
     * Renders this engine's {@code QueueName} as a JSON string.
     * <p>
     * Registered here rather than assumed, because the admin API's own Jackson module only covers
     * {@code CharSequenceType}, and this engine's {@code QueueName} deliberately is not one.
     * Without it the same concept renders as an object on these endpoints and as a string on the
     * durable-queues ones.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardOwnedQueueJacksonModule shardOwnedQueueJacksonModule() {
        return new ShardOwnedQueueJacksonModule();
    }
}
