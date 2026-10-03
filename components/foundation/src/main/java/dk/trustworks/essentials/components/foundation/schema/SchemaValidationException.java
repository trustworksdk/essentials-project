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
package dk.trustworks.essentials.components.foundation.schema;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The database does not have the schema the contributors describe - thrown by a validating {@link SchemaApplier}, at
 * startup or when a dynamic contributor registers an object.
 */
public final class SchemaValidationException extends RuntimeException {
    private final List<String> problems;

    /**
     * @param message  the summary, including every problem
     * @param problems one line per change that is missing or differs
     */
    public SchemaValidationException(String message, List<String> problems) {
        super(message);
        this.problems = List.copyOf(requireNonNull(problems, "No problems provided"));
    }

    /**
     * @return one line per change that is missing or differs from what is recorded
     */
    public List<String> problems() {
        return problems;
    }
}
