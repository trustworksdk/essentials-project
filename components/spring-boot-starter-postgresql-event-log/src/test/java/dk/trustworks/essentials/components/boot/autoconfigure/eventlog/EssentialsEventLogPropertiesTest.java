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

import dk.trustworks.essentials.components.eventlog.EventLogSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialsEventLogPropertiesTest {

    @Test
    void the_defaults_equal_the_engine_defaults() {
        var properties = new EssentialsEventLogProperties();
        assertThat(properties.toSettings()).isEqualTo(EventLogSettings.defaults());
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isInitializeSchema()).isTrue();
    }

    @Test
    void every_property_reaches_the_settings() {
        var values = new LinkedHashMap<String, String>();
        values.put("essentials.event-log.backstop-interval", "2s");
        values.put("essentials.event-log.tail-page-size", "50");
        values.put("essentials.event-log.probe-backoff-min", "3s");
        values.put("essentials.event-log.probe-backoff-max", "20s");
        values.put("essentials.event-log.progress-save-interval", "0s");
        values.put("essentials.event-log.hole-range-threshold", "77");
        values.put("essentials.event-log.generic-plans", "false");
        values.put("essentials.event-log.subscription-buffer-size", "900");
        values.put("essentials.event-log.catch-up-page-size", "250");
        values.put("essentials.event-log.page-size", "40");
        values.put("essentials.event-log.retry-attempts", "5");
        values.put("essentials.event-log.retry-backoff-initial", "20ms");
        values.put("essentials.event-log.retry-backoff-max", "30s");
        values.put("essentials.event-log.catch-up-permits", "6");
        values.put("essentials.event-log.catch-up-scan-window", "1000");
        values.put("essentials.event-log.catch-up-scan-window-max", "50000");
        values.put("essentials.event-log.event-type-indexed", "Orders,Accounts");
        values.put("essentials.event-log.tenant-indexed", "Orders");
        values.put("essentials.event-log.woken-probe-min", "15ms");
        values.put("essentials.event-log.cross-jvm-wake-up", "false");
        values.put("essentials.event-log.woken-read-min", "5ms");
        values.put("essentials.event-log.notify-send-min", "7ms");
        values.put("essentials.event-log.lease-duration", "30s");
        values.put("essentials.event-log.lease-renewal-interval", "5s");
        values.put("essentials.event-log.lease-local-stop", "10s");
        values.put("essentials.event-log.lease-statement-timeout", "4s");
        values.put("essentials.event-log.node-name", "node-a");
        values.put("essentials.event-log.parked-check-interval", "9s");
        values.put("essentials.event-log.admin-list-limit", "250");
        values.put("essentials.event-log.hole-probe-wait-max", "40ms");

        var properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("essentials.event-log", EssentialsEventLogProperties.class)
                .get();

        var expected = new EventLogSettings(Duration.ofSeconds(2), 50, Duration.ofSeconds(3), Duration.ofSeconds(20), Duration.ZERO, 77, false, 900, 250, 40, 5,
                                            Duration.ofMillis(20), Duration.ofSeconds(30), 6, 1000, 50000, Set.of("Orders", "Accounts"), Set.of("Orders"),
                                            Duration.ofMillis(15), false, Duration.ofMillis(5), Duration.ofMillis(7), Duration.ofSeconds(30), Duration.ofSeconds(5),
                                            Duration.ofSeconds(10), Duration.ofSeconds(4), "node-a", Duration.ofSeconds(9), 250,
                                            Duration.ofMillis(40));
        assertThat(properties.toSettings()).isEqualTo(expected);
        // Every component differs from its default, so a property that does not reach its component cannot pass unnoticed
        var defaults = EventLogSettings.defaults();
        for (var component : EventLogSettings.class.getRecordComponents()) {
            try {
                assertThat(component.getAccessor().invoke(expected))
                        .as(component.getName())
                        .isNotEqualTo(component.getAccessor().invoke(defaults));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        assertThat(values).hasSize(EventLogSettings.class.getRecordComponents().length);
    }
}
