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
 * <h2>What stayed here and what moved</h2>
 * The {@code ShardOwnedQueuesApi} and the Jackson module are engine-side and stay. The controller
 * moved to {@code spring-boot-starter-admin-api}, where every other admin controller lives and where
 * the convention says it belongs — publishing the engine is what made that legal. It is registered
 * there {@code @ConditionalOnBean(ShardOwnedQueuesApi.class)}, so it appears only where this class
 * has declared that bean, which is the same shape CDC and the event store already use.
 * <p>
 * That also fixed an error-mapping fault: {@code AdminApiExceptionHandler} is package-scoped to the
 * admin API starter's own {@code rest} package, so while the controller sat outside it a 404 reached
 * the client as a 500. A subclass re-scoping the advice was the stopgap; moving the controller
 * removes the need for one.
 *
 * <h2>Why this is a separate auto-configuration, and where it sits</h2>
 * Its beans are {@code @ConditionalOnBean}, and that condition is evaluated in auto-configuration
 * order: look for a bean before it is defined and the whole class silently backs off. So this one
 * must run <em>after</em> {@code EssentialsComponentsConfiguration}, which declares the
 * {@code EssentialsSecurityProvider}, and <em>before</em> {@code EssentialsAdminApiAutoConfiguration},
 * which registers the controller {@code @ConditionalOnBean} of the API bean declared here.
 * <p>
 * {@link ShardOwnedQueueAutoConfiguration} cannot carry it, because that one is ordered <em>before</em>
 * {@code EssentialsComponentsConfiguration} so its {@code DurableQueues} bean can displace the
 * default. One class cannot be both before and after the same configuration.
 * <p>
 * Both halves of this have already failed once in production-shaped ways, and neither failed loudly:
 * a {@code @ConditionalOnBean} that backs off logs nothing and simply leaves the endpoints returning
 * 404.
 */
@AutoConfiguration(
        afterName = {
                // EssentialsSecurityProvider, which the Api bean below is @ConditionalOnBean of.
                "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration",
                "dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.ShardOwnedQueueAutoConfiguration"},
        beforeName =
                // The admin API registers ShardOwnedQueuesController @ConditionalOnBean of the Api
                // bean declared here, so it has to exist by the time that class is evaluated.
                "dk.trustworks.essentials.components.boot.autoconfigure.admin.api.EssentialsAdminApiAutoConfiguration")
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
