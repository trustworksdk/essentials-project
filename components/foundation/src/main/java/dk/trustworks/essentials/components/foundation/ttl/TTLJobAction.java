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
     * Constructs a function call string representation that includes the function name and its
     * invocation arguments. The generated string is typically formatted as:
     * "<function_name>(<arg1>, <arg2>, ...)". This serves as the invocation statement
     * for execution of the associated TTL job function.
     *
     * @return a string representing the full function call, including the function name
     *         and its argument list in the format "<function_name>(<args>);".
     */
    String buildFunctionCall();

    /**
     * Extracts and returns the function name from a generated function call string.
     * The function call string is constructed using the {@code buildFunctionCall()} method,
     * and the extracted name is the substring before the first opening parenthesis.
     *
     * @return the extracted function name from the function call string.
     */
    default String functionName() {
        String inv = buildFunctionCall();
        return inv.substring(0, inv.indexOf('('));
    }

    /**
     * Extracts and returns the argument list from a function call string.
     * The function call string is generated using the {@code buildFunctionCall()} method
     * and is expected to follow the format "<function_name>(<arg1>, <arg2>, …)".
     * This method isolates and returns the substring between the opening
     * and closing parentheses, which represents the function arguments.
     *
     * @return a string containing the arguments of the function call, separated by commas.
     */
    default String invocationArgs() {
        String inv = buildFunctionCall();
        return inv.substring(inv.indexOf('(') + 1, inv.lastIndexOf(')'));
    }

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
