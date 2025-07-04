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

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents the definition of a Time-To-Live (TTL) job. A TTL job is used to manage
 * the lifecycle of data by applying actions based on expiration policies and defined schedules.
 *
 * @param action                the action associated with the TTL job. Defines the behavior
 *                              to be executed during the job - see {@link TTLJobAction#create(String, String, String, String)}/{@link DefaultTTLJobAction} and their <b>associated security warnings!</b>
 * @param scheduleConfiguration the configuration specifying the scheduling details of the TTL job.
 */
public record TTLJobDefinition(TTLJobAction action, ScheduleConfiguration scheduleConfiguration) {


    public TTLJobDefinition {
        requireNonNull(action, "Action required");
        requireNonNull(scheduleConfiguration, "Schedule configuration required");
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TTLJobDefinition that = (TTLJobDefinition) o;
        return Objects.equals(action.jobName(), that.action.jobName());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(action.jobName());
    }
}