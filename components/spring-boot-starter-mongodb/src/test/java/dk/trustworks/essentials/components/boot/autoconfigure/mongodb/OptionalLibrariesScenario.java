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

package dk.trustworks.essentials.components.boot.autoconfigure.mongodb;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import java.util.*;

/**
 * What an application experiences when it starts the MongoDB starter with one of its optional dependencies missing from
 * the classpath - Micrometer Tracing, Micrometer altogether, or Spring Boot's health contributor API. Loaded and run by
 * {@link StarterAutoConfigurationIT} through a class loader without those jars, so Spring and the starter's
 * auto-configuration link against that reduced classpath - which a {@code FilteredClassLoader} cannot show.
 * <p>
 * When micrometer-core is on the classpath the scenario registers a {@code SimpleMeterRegistry}, so the beans that
 * need a {@code MeterRegistry} bean show up; it names Micrometer and Spring Boot health types only by name, so it
 * links on every one of those classpaths.
 */
public final class OptionalLibrariesScenario {
    /**
     * The beans that need an optional dependency, whose presence {@link #startTheStarter} reports:
     * {@code durableQueuesMicrometerInterceptor} and {@code micrometerDurableQueueMessageObserver} (micrometer-core
     * and a {@code MeterRegistry} bean), {@code durableQueuesHealthIndicator} (spring-boot-health) and
     * {@code durableQueuesMicrometerTracingInterceptor} (micrometer-tracing and a {@code Tracer} bean, which the
     * scenario never has)
     */
    public static final List<String> OPTIONAL_BEANS = List.of("durableQueuesMicrometerInterceptor", "micrometerDurableQueueMessageObserver", "durableQueuesHealthIndicator", "durableQueuesMicrometerTracingInterceptor");

    private static final String SIMPLE_METER_REGISTRY = "io.micrometer.core.instrument.simple.SimpleMeterRegistry";

    private OptionalLibrariesScenario() {
    }

    /**
     * Starts the starter against the given replica set.
     *
     * @return the names of the {@link #OPTIONAL_BEANS} the context holds, comma separated in that order - empty if it
     * holds none
     * @throws Throwable the context's startup failure, if it failed to start
     */
    @SuppressWarnings("unchecked")
    public static String startTheStarter(String mongoUri) throws Throwable {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class,
                                                         DataMongoAutoConfiguration.class,
                                                         EssentialsComponentsConfiguration.class))
                .withPropertyValues("spring.mongodb.uri=" + mongoUri,
                                    "essentials.life-cycles.start-life-cycles=false");
        if (ClassUtils.isPresent(SIMPLE_METER_REGISTRY, OptionalLibrariesScenario.class.getClassLoader())) {
            runner = runner.withBean((Class<Object>) Class.forName(SIMPLE_METER_REGISTRY, true, OptionalLibrariesScenario.class.getClassLoader()));
        }
        var presentBeans = new ArrayList<String>();
        runner.run(ctx -> {
            if (ctx.getStartupFailure() != null) {
                throw ctx.getStartupFailure();
            }
            // By name: the beans' classes cannot be linked without the optional dependency they need
            OPTIONAL_BEANS.stream().filter(ctx::containsBean).forEach(presentBeans::add);
        });
        return String.join(",", presentBeans);
    }
}
