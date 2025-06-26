/*
 * Copyright 2021-2025 the original author or authors.
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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A builder class for constructing {@link TTLJobDefinition} instances.
 * This class allows the configuration of the action and schedule details
 * required for the definition of a Time-To-Live (TTL) job.
 * <p>
 * A TTL job is used to manage the lifecycle of data by executing specific
 * actions based on expiration rules and schedules.
 */
public class TTLJobDefinitionBuilder {

    private TTLJobAction action;
    private ScheduleConfiguration scheduleConfiguration;

    public TTLJobDefinitionBuilder withAction(TTLJobAction action) {
        this.action = action;
        return this;
    }

    public TTLJobDefinitionBuilder withSchedule(ScheduleConfiguration scheduleConfiguration) {
        this.scheduleConfiguration = scheduleConfiguration;
        return this;
    }

    public TTLJobDefinition build() {
        requireNonNull(action, "Action required");
        requireNonNull(scheduleConfiguration, "Schedule configuration required");
        return new TTLJobDefinition(action, scheduleConfiguration);
    }
}
