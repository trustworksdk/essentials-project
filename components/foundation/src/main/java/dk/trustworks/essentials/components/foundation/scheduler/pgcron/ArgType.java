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

/**
 * The {@code ArgType} enum defines the types of arguments that can be used in structured
 * data contexts, such as SQL statements, based on their intended usage or categorization.
 */
public enum ArgType {
    /**
     * Represents an argument type categorized as an identifier.
     * This type is used for SQL identifiers such as table or column names.
     */
    IDENTIFIER,
    /**
     * Represents an argument type categorized as a literal.
     * This type is used to define values that are treated as
     * raw SQL literals without modification or interpretation.
     */
    LITERAL,
    /**
     * Represents an argument type categorized as an expression.
     * This type is used for SQL expressions, where the expression is serialized into
     * its string representation using a provided serializer.
     * No additional validation is applied to the expression itself.
     */
    EXPRESSION
}
