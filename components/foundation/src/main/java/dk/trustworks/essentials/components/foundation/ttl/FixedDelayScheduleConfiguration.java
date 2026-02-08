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

package dk.trustworks.essentials.components.foundation.ttl;

import dk.trustworks.essentials.components.foundation.scheduler.executor.FixedDelay;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a schedule configuration based on a fixed delay mechanism.
 * This record wraps a {@link FixedDelay} instance, which defines the
 * initial delay and the fixed period between subsequent task executions.
 */
public record FixedDelayScheduleConfiguration(FixedDelay fixedDelay) implements ScheduleConfiguration {

    public FixedDelayScheduleConfiguration {
        requireNonNull(fixedDelay, "fixedDelay must not be null");
    }
}
