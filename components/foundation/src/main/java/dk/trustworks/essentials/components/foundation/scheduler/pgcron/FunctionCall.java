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

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static java.util.stream.Collectors.joining;


/**
 * Represents a call to a SQL function with a name and a list of arguments. This class
 * checks the function name is valid and provides functionality to construct a SQL-compatible
 * representation of the function call.
 * <p>
 * Instances of this class are immutable and thread-safe.
 * </p>
 *
 * @param functionName the name of the SQL function. Must not be null
 *                     and must match specific validation rules for a SQL function name using {@link PostgresqlUtil#isValidFunctionName(String)}
 * @param args         a list of {@link Arg} objects acting as the arguments to the function.
 *                     Must not be null.
 * @see {@link Arg}
 */
public record FunctionCall(String functionName, List<Arg<?>> args) {

    /**
     * Represents a call to a SQL function with a name and a list of arguments. This class
     * checks the function name is valid and provides functionality to construct a SQL-compatible
     * representation of the function call.
     * <p>
     * Instances of this class are immutable and thread-safe.
     * </p>
     *
     * @param functionName the name of the SQL function. Must not be null
     *                     and must match specific validation rules for a SQL function name using {@link PostgresqlUtil#isValidFunctionName(String)}
     * @param args         a list of {@link Arg} objects acting as the arguments to the function.
     *                     Must not be null.
     * @throws IllegalArgumentException if {@code functionName} or {@code args} is null or if {@code functionName} does not pass validation.
     */
    public FunctionCall {
        requireNonNull(functionName, "functionName cannot be null");
        requireNonNull(args, "args cannot be null");
        if (!PostgresqlUtil.isValidFunctionName(functionName)) {
            throw new IllegalArgumentException(msg("Invalid function name '{}' ", functionName));
        }
    }

    /**
     * Converts the {@link FunctionCall} instance into its SQL string representation.
     * This method constructs the SQL representation of the function call by joining
     * the serialized SQL representations of its arguments (via {@link Arg#toSql()}) and
     * combining them with the function name in the appropriate format.
     * <p>
     * For example, if the {@link FunctionCall} represents a function <code>my_function</code> with two arguments <code>'column1'</code> and <code>'column2'</code>,
     * this method would return the string:
     * <code>
     * my_function('column1', 'column2')
     * </code>
     *
     * @return the SQL string representation of the function call in the format:
     * {@code functionName(arg1, arg2, ...)}.
     * If there are no arguments, the result will be:
     * {@code functionName()}.
     */
    public String toSql() {
        String joined = args.stream()
                            .map(Arg::toSql)
                            .collect(joining(", "));
        return functionName + "(" + joined + ")";
    }
}