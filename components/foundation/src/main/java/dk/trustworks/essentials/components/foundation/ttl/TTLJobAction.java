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

import dk.trustworks.essentials.components.foundation.scheduler.pgcron.FunctionCall;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

/**
 * Represents an action that relates to a Time-To-Live (TTL) job.
 * This defines the behavior for executing, validating, and describing a specific TTL job action.
 */
public interface TTLJobAction {

    /**
     * Retrieves the unique name of the TTL job associated with this action.
     *
     * @return the TTL job name, which uniquely identifies the job.
     */
    String jobName();

    /**
     * Provides the FunctionCall associated with the TTL job action. This represents
     * an SQL function call used within the action to execute specific TTL job behavior.
     *
     * @return the FunctionCall instance containing the function name and its arguments.
     */
    FunctionCall functionCall();

    /**
     * Executes a Time-To-Live (TTL) job action directly using the provided factory.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing instances
     *                          of {@link HandleAwareUnitOfWork} for this execution.
     */
    void executeDirectly(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory);

    /**
     * Validate action sql syntax without executing side effects.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing instances
     *                          of {@link HandleAwareUnitOfWork}, which is used during validation.
     */
    void validate(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory);
}
