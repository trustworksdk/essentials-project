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

import dk.trustworks.essentials.components.eventlog.EventLogObserver;

/**
 * The observer the starter gives the {@code EventLog} when the application declares no {@link EventLogObserver} bean of
 * its own: a {@code MicrometerEventLogObserver} when a {@code MeterRegistry} bean exists.
 * <p>
 * A type of this starter's own, so the {@code eventLog} bean's signature names no Micrometer class: Micrometer is an
 * optional dependency, and the binding that uses it is only defined when it is on the class path (see
 * {@code EventLogAutoConfiguration.MicrometerMetricsConfiguration}), as the shard-owned queue starter does.
 */
@FunctionalInterface
public interface EventLogMetrics {
    /**
     * @return the observer to install, or {@code null} for none (no registry in the context)
     */
    EventLogObserver observer();
}
