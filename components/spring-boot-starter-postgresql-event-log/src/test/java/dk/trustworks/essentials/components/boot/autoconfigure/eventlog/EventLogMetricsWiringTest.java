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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsProperties;
import dk.trustworks.essentials.components.eventlog.observability.micrometer.MicrometerEventLogObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventLogAutoConfiguration.MicrometerMetricsConfiguration} without a database: the Micrometer observer is what the
 * {@code eventLog} bean gets when a {@link MeterRegistry} bean exists, and nothing without one, with the property off or
 * without Micrometer on the class path. That an application observer bean wins is the {@code eventLog} bean's own rule,
 * covered in {@link EventLogAutoConfigurationIT}.
 */
class EventLogMetricsWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EventLogAutoConfiguration.MicrometerMetricsConfiguration.class)
            .withBean(EssentialsComponentsProperties.class);

    @Test
    void with_a_registry_the_engine_gets_the_micrometer_observer() {
        runner.withBean(SimpleMeterRegistry.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EventLogMetrics.class).observer()).isInstanceOf(MicrometerEventLogObserver.class);
        });
    }

    @Test
    void without_a_registry_the_engine_gets_no_observer() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EventLogMetrics.class).observer()).isNull();
        });
    }

    @Test
    void switched_off_by_property() {
        runner.withBean(SimpleMeterRegistry.class)
              .withPropertyValues("essentials.event-log.metrics.enabled=false")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(EventLogMetrics.class));
    }

    @Test
    void without_micrometer_on_the_class_path_nothing_is_declared() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
              .run(ctx -> assertThat(ctx).doesNotHaveBean(EventLogMetrics.class));
    }
}
