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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.micrometer.metrics.autoconfigure.*;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What an application with Micrometer metrics and observation but without micrometer-tracing on its classpath - Spring
 * Boot Actuator without a tracer - experiences when it starts the event store starter. Loaded and run by
 * {@link StarterAutoConfigurationIT} through a class loader that cannot see Micrometer Tracing, so Spring and the
 * starters' auto-configurations link against that reduced classpath - which a {@code FilteredClassLoader} cannot show.
 */
public final class WithoutMicrometerTracingScenario {
    private WithoutMicrometerTracingScenario() {
    }

    /**
     * Starts the PostgreSQL and event store starters, with Spring Boot's metrics and observation auto-configurations,
     * against the given database.
     *
     * @return the names of the event store and durable queues tracing interceptor beans the context holds, comma
     * separated - empty if it holds neither
     * @throws Throwable the context's startup failure, if it failed to start
     */
    public static String startTheStarter(String jdbcUrl, String username, String password) throws Throwable {
        var tracingInterceptors = new StringBuilder();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                         DataSourceTransactionManagerAutoConfiguration.class,
                                                         ObservationAutoConfiguration.class,
                                                         MetricsAutoConfiguration.class,
                                                         SimpleMetricsExportAutoConfiguration.class,
                                                         CompositeMeterRegistryAutoConfiguration.class,
                                                         EssentialsComponentsConfiguration.class,
                                                         EventStoreConfiguration.class,
                                                         SnapshotConfiguration.class,
                                                         ClosingBooksConfiguration.class,
                                                         AggregateLifecycleApiConfiguration.class,
                                                         AggregateArchiveApiConfiguration.class))
                .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                .withPropertyValues("spring.datasource.url=" + jdbcUrl,
                                    "spring.datasource.username=" + username,
                                    "spring.datasource.password=" + password,
                                    "essentials.life-cycles.start-life-cycles=false")
                .run(ctx -> {
                    if (ctx.getStartupFailure() != null) {
                        throw ctx.getStartupFailure();
                    }
                    // By name: the interceptors' classes cannot be linked without Micrometer Tracing
                    for (var name : new String[]{"micrometerTracingEventStoreInterceptor", "durableQueuesMicrometerTracingInterceptor"}) {
                        if (ctx.containsBean(name)) {
                            tracingInterceptors.append(tracingInterceptors.isEmpty() ? "" : ",").append(name);
                        }
                    }
                });
        return tracingInterceptors.toString();
    }
}
