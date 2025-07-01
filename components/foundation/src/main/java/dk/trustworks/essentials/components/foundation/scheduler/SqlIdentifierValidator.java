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

package dk.trustworks.essentials.components.foundation.scheduler;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class to validate SQL identifiers, specifically function names.
 * Provides methods and patterns for determining the validity of identifiers
 * based on SQL naming conventions and reserved keyword restrictions.
 */
public class SqlIdentifierValidator {

    public static final Pattern FN_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    private static final Set<String> SQL_RESERVED_WORDS = Set.of(
            "select", "insert", "update", "delete", "drop", "create", "alter",
            "grant", "revoke", "union", "exec", "execute", "sp_executesql"
                                                                );

    public static final Pattern QUALIFIED_FN_NAME =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}\\.[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    public static boolean isValidFunctionName(String functionName) {
        if (functionName == null || functionName.trim().isEmpty()) {
            return false;
        }

        String cleaned = functionName.toLowerCase().trim();

        if (SQL_RESERVED_WORDS.contains(cleaned)) {
            return false;
        }

        return FN_NAME.matcher(functionName).matches() ||
                QUALIFIED_FN_NAME.matcher(functionName).matches();
    }
}
