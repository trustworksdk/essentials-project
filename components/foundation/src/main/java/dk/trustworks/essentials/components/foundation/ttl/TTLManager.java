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

/**
 * Interface for managing Time-To-Live (TTL) jobs. The TTLManager is responsible for
 * scheduling and managing actions that enforce data lifecycle operations
 * such as deletion or archival based on expiration policies.
 */
public interface TTLManager {

    final String DEFAULT_TTL_FUNCTION_NAME = "essentials_ttl_function";

    /**
     * Schedules a Time-To-Live (TTL) job for execution based on the provided job definition.
     * This method registers the job and configures it according to the defined action
     * and schedule.
     *
     * @param jobDefinition the definition of the TTL job to be scheduled, including its action
     *                      and scheduling configuration. Must not be null.
     */
    void scheduleTTLJob(TTLJobDefinition jobDefinition);
}
