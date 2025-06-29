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
 * Represents a scheduled job in the scheduling system. This interface defines
 * a contract for jobs that can be scheduled for execution.
 * <p>
 * Implementations of this interface must provide a name that uniquely identifies
 * the job. The name is typically used for logging, debugging, or as an identifier
 * when interacting with the scheduling system.
 */
public interface EssentialsScheduledJob {

    /**
     * Retrieves the name of the scheduled job.
     * <p>
     * The name has be unique and will be a combination of the given name and node instance id.
     *
     * @return the unique name identifying the job
     */
    String name();
}
