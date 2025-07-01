/*
 *
 *  * Copyright 2021-2025 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import dk.trustworks.essentials.components.foundation.scheduler.SqlIdentifierValidator;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static java.util.stream.Collectors.joining;

public record FunctionCall(String functionName, List<Arg<?>> args) {

    public FunctionCall {
        requireNonNull(functionName, "functionName cannot be null");
        requireNonNull(args, "args cannot be null");
        if (!SqlIdentifierValidator.isValidFunctionName(functionName)) {
            throw new IllegalArgumentException(msg("Invalid function name '{}' ", functionName));
        }
    }

    public String toSql() {
        String joined = args.stream()
                            .map(Arg::toSql)
                            .collect(joining(", "));
        return functionName + "(" + joined + ")";
    }
}
