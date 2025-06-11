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

package dk.trustworks.essentials.components.foundation.scheduler.executor;

import dk.trustworks.essentials.components.foundation.scheduler.EssentialsScheduledJob;

/**
 * Represents a scheduled job that can be executed by an executor with a specified fixed delay.
 * This record encapsulates the job's name that has to be unique when saved to storage name and fixed delay is combined for uniqueness, its fixed delay configuration for scheduling,
 * and the task to be executed.
 * <p>
 * This implementation conforms to the EssentialsScheduledJob interface, allowing it to
 * be used within the scheduling system.
 * <p>
 * The fixed delay specifies the scheduling parameters, such as the initial delay, the period between
 * task executions, and the time unit of these delays.
 */
public record ExecutorJob(String name, FixedDelay fixedDelay, Runnable task) implements EssentialsScheduledJob {

    /**
     * Returns the name of this scheduled job concatenated with its fixed delay configuration as a string.
     * The resulting string uniquely identifies the job and includes its name and scheduling details.
     *
     * @return the concatenation of the job's name and its fixed delay configuration.
     */
    @Override
    public String name() {
        return name + "_" + fixedDelay.toString();
    }
}
