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

package dk.trustworks.essentials.components.foundation.scheduler;

/**
 * Represents a scheduler responsible for scheduling jobs defined by the EssentialsScheduledJob interface.
 * This interface provides a method for scheduling various types of jobs.
 * <p>
 * ** Note: This scheduler is not intended to replace a full-fledged scheduler such as Quartz or Spring, it is a simple
 * scheduler that utilizes the postgresql pg_cron extension if available or a simple ScheduledExecutorService to schedule jobs. **
 */
public interface EssentialsScheduler {

    /**
     * Schedules the specified job for execution within the scheduler. The scheduling mechanism
     * may depend on the availability of the PostgreSQL pg_cron extension or use a simple
     * ScheduledExecutorService as a fallback.
     *
     * @param job the job to be scheduled, must implement the EssentialsScheduledJob interface
     *            and provide a unique name for identification
     * @return true if the job was successfully scheduled, false otherwise
     */
    boolean scheduleJob(EssentialsScheduledJob job);
}
