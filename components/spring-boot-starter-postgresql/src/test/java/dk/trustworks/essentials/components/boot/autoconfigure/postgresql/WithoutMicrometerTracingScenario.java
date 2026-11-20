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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What an application without micrometer-tracing on its classpath experiences when it starts this starter. Loaded and
 * run by {@link StarterAutoConfigurationIT} through a class loader that cannot see Micrometer Tracing, so Spring and the
 * starter's auto-configuration link against that reduced classpath - which a {@code FilteredClassLoader} cannot show.
 */
public final class WithoutMicrometerTracingScenario {
    private WithoutMicrometerTracingScenario() {
    }

    /**
     * Starts the starter against the given database.
     *
     * @return {@code "true"} if the context holds the durable queues tracing interceptor, {@code "false"} if not
     * @throws Throwable the context's startup failure, if it failed to start
     */
    public static String startTheStarter(String jdbcUrl, String username, String password) throws Throwable {
        var hasTracingInterceptor = new AtomicBoolean();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                         DataSourceTransactionManagerAutoConfiguration.class,
                                                         EssentialsComponentsConfiguration.class))
                .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                .withPropertyValues("spring.datasource.url=" + jdbcUrl,
                                    "spring.datasource.username=" + username,
                                    "spring.datasource.password=" + password,
                                    "essentials.life-cycles.start-life-cycles=false")
                .run(ctx -> {
                    if (ctx.getStartupFailure() != null) {
                        throw ctx.getStartupFailure();
                    }
                    // By name: the interceptor's class cannot be linked without Micrometer Tracing
                    hasTracingInterceptor.set(ctx.containsBean("durableQueuesMicrometerTracingInterceptor"));
                });
        return String.valueOf(hasTracingInterceptor.get());
    }
}
