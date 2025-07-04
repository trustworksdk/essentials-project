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

package dk.trustworks.essentials.components.foundation.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PostgresqlUtilTest {
    @Test
    void testValidName() {
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("ValidTableName")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("valid_column_name")).doesNotThrowAnyException();
    }

    @Test
    void testWithReservedKeyword() {
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("SELECT"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("is a reserved keyword");
    }

    @Test
    void testWithInvalidCharacters() {
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("Invalid-Name"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
    }

    @Test
    void testStartsWithDigit() {
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("123InvalidName"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
    }

    @Test
    void testEmptyString() {
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName(""))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void testNullInput() {
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName(null))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void testSimpleSqlInjection() {
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("users; DROP TABLE users;--"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("1 OR 1=1"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
    }

    @Test
    public void testIsValidFunctionName_ValidNonQualifiedNames() {
        // Valid non-qualified function names
        assertThat(PostgresqlUtil.isValidFunctionName("valid_function_name")).isTrue();
        assertThat(PostgresqlUtil.isValidFunctionName("_valid_function_name")).isTrue();
        assertThat(PostgresqlUtil.isValidFunctionName("validFunctionName")).isTrue();
        assertThat(PostgresqlUtil.isValidFunctionName("valid123")).isTrue();
    }

    @Test
    public void testIsValidFunctionName_InvalidNonQualifiedNames() {
        // Invalid non-qualified function names (pattern doesn't match)
        assertThat(PostgresqlUtil.isValidFunctionName("123invalid")).isFalse(); // Starts with a number
        assertThat(PostgresqlUtil.isValidFunctionName("invalid-name")).isFalse(); // Contains a hyphen
        assertThat(PostgresqlUtil.isValidFunctionName("invalid..name")).isFalse(); // Contains two dots, not a valid qualified name
        assertThat(PostgresqlUtil.isValidFunctionName("")).isFalse(); // Empty string
        assertThat(PostgresqlUtil.isValidFunctionName(null)).isFalse(); // Null
    }

    @Test
    public void testIsValidFunctionName_ValidQualifiedNames() {
        // Valid qualified function names
        assertThat(PostgresqlUtil.isValidFunctionName("schema.my_func")).isTrue();
        assertThat(PostgresqlUtil.isValidFunctionName("schema_name.my_func_name")).isTrue();
        assertThat(PostgresqlUtil.isValidFunctionName("_schema._my_func")).isTrue();
        assertThat(PostgresqlUtil.isValidFunctionName("schema123.my_func123")).isTrue();
    }

    @Test
    public void testIsValidFunctionName_InvalidQualifiedNames() {
        // Invalid qualified function names (pattern doesn't match)
        assertThat(PostgresqlUtil.isValidFunctionName("123schema.function")).isFalse(); // Schema starts with a number
        assertThat(PostgresqlUtil.isValidFunctionName("schema.123function")).isFalse(); // Function starts with a number
        assertThat(PostgresqlUtil.isValidFunctionName("schema-name.function")).isFalse(); // Schema contains a hyphen
        assertThat(PostgresqlUtil.isValidFunctionName("schema.function-name")).isFalse(); // Function contains a hyphen
        assertThat(PostgresqlUtil.isValidFunctionName("schema..function")).isFalse(); // Contains two dots
        assertThat(PostgresqlUtil.isValidFunctionName("schema.function.")).isFalse(); // Ends with a dot
    }

    @Test
    public void testIsValidFunctionName_ReservedKeywords() {
        // Non-qualified function names that are reserved keywords
        assertThat(PostgresqlUtil.isValidFunctionName("SELECT")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("FROM")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("WHERE")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("TABLE")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("FUNCTION")).isFalse();
    }

    @Test
    public void testIsValidFunctionName_QualifiedNamesWithReservedKeywords() {
        // Qualified function names where one part is a reserved keyword
        assertThat(PostgresqlUtil.isValidFunctionName("SELECT.function")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("schema.SELECT")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("FROM.function")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("schema.FROM")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("TABLE.function")).isFalse();
        assertThat(PostgresqlUtil.isValidFunctionName("schema.TABLE")).isFalse();
    }

}