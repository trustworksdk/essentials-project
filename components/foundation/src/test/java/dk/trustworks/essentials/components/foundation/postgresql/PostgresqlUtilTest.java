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
                .hasMessageContaining("Invalid table or column name: 'SELECT'");
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

    @Test
    public void testIsValidSqlIdentifier_ValidIdentifiers() {
        // Valid SQL identifiers
        assertThat(PostgresqlUtil.isValidSqlIdentifier("valid_identifier")).isTrue();
        assertThat(PostgresqlUtil.isValidSqlIdentifier("_valid_identifier")).isTrue();
        assertThat(PostgresqlUtil.isValidSqlIdentifier("validIdentifier")).isTrue();
        assertThat(PostgresqlUtil.isValidSqlIdentifier("valid123")).isTrue();
        assertThat(PostgresqlUtil.isValidSqlIdentifier("a")).isTrue(); // Single character
    }

    @Test
    public void testIsValidSqlIdentifier_InvalidIdentifiers() {
        // Invalid SQL identifiers
        assertThat(PostgresqlUtil.isValidSqlIdentifier("123invalid")).isFalse(); // Starts with number
        assertThat(PostgresqlUtil.isValidSqlIdentifier("invalid-name")).isFalse(); // Contains hyphen
        assertThat(PostgresqlUtil.isValidSqlIdentifier("invalid.name")).isFalse(); // Contains dot
        assertThat(PostgresqlUtil.isValidSqlIdentifier("")).isFalse(); // Empty string
        assertThat(PostgresqlUtil.isValidSqlIdentifier(null)).isFalse(); // Null
        assertThat(PostgresqlUtil.isValidSqlIdentifier("   ")).isFalse(); // Whitespace only
        assertThat(PostgresqlUtil.isValidSqlIdentifier("SELECT")).isFalse(); // Reserved keyword
    }

    @Test
    public void testIsValidSqlIdentifier_LengthLimits() {
        // Test length limits
        String maxLengthIdentifier = "a".repeat(PostgresqlUtil.MAX_IDENTIFIER_LENGTH);
        String tooLongIdentifier = "a".repeat(PostgresqlUtil.MAX_IDENTIFIER_LENGTH + 1);

        assertThat(PostgresqlUtil.isValidSqlIdentifier(maxLengthIdentifier)).isTrue();
        assertThat(PostgresqlUtil.isValidSqlIdentifier(tooLongIdentifier)).isFalse();
    }

    @Test
    public void testIsValidQualifiedSqlIdentifier_ValidQualifiedIdentifiers() {
        // Valid qualified SQL identifiers
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.entity")).isTrue();
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema_name.entity_name")).isTrue();
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("_schema._entity")).isTrue();
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema123.entity123")).isTrue();
    }

    @Test
    public void testIsValidQualifiedSqlIdentifier_InvalidQualifiedIdentifiers() {
        // Invalid qualified SQL identifiers
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("123schema.entity")).isFalse(); // Schema starts with number
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.123entity")).isFalse(); // Entity starts with number
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema-name.entity")).isFalse(); // Schema contains hyphen
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.entity-name")).isFalse(); // Entity contains hyphen
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema..entity")).isFalse(); // Double dot
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier(".schema.entity")).isFalse(); // Starts with dot
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.entity.")).isFalse(); // Ends with dot
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema")).isFalse(); // No dot
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.entity.extra")).isFalse(); // Too many parts
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("")).isFalse(); // Empty string
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier(null)).isFalse(); // Null
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("SELECT.entity")).isFalse(); // Reserved keyword in schema
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.SELECT")).isFalse(); // Reserved keyword in entity
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.TABLE")).isFalse(); // Reserved keyword "TABLE" in entity
    }

    @Test
    public void testIsValidQualifiedSqlIdentifier_TotalLengthLimits() {
        // Test total length limits for qualified identifiers
        String longButValidPart = "a".repeat(60); // 60 chars each part
        String qualifiedIdentifier = longButValidPart + "." + longButValidPart; // 121 chars total (within limit)
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier(qualifiedIdentifier)).isTrue();

        // Test identifier that exceeds total length limit
        String tooLongPart = "a".repeat(64); // 64 chars each part  
        String tooLongQualifiedIdentifier = tooLongPart + "." + tooLongPart; // 129 chars total (exceeds limit)
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier(tooLongQualifiedIdentifier)).isFalse();

        // Test edge case: exactly at the limit
        String maxLengthQualified = "a".repeat(63) + "." + "a".repeat(63); // 127 chars total (at limit)
        assertThat(PostgresqlUtil.isValidQualifiedSqlIdentifier(maxLengthQualified)).isTrue();
    }

    @Test
    public void testIsValidFunctionName_TotalLengthLimits() {
        // Test that function name validation now includes total length checking
        String longButValidPart = "a".repeat(60);
        String qualifiedFunctionName = longButValidPart + "." + longButValidPart; // 121 chars total
        assertThat(PostgresqlUtil.isValidFunctionName(qualifiedFunctionName)).isTrue();

        // Test function name that exceeds total length limit
        String tooLongPart = "a".repeat(64);
        String tooLongQualifiedFunctionName = tooLongPart + "." + tooLongPart; // 129 chars total
        assertThat(PostgresqlUtil.isValidFunctionName(tooLongQualifiedFunctionName)).isFalse();

        // Test edge case: exactly at the limit for qualified names
        String maxLengthQualified = "a".repeat(63) + "." + "a".repeat(63); // 127 chars total
        assertThat(PostgresqlUtil.isValidFunctionName(maxLengthQualified)).isTrue();

        // Test single identifier length limit
        String tooLongSingleIdentifier = "a".repeat(PostgresqlUtil.MAX_IDENTIFIER_LENGTH + 1);
        assertThat(PostgresqlUtil.isValidFunctionName(tooLongSingleIdentifier)).isFalse();
    }

    @Test
    public void testCheckIsValidTableOrColumnName_ValidNonQualifiedNames() {
        // Valid non-qualified table/column names
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("valid_table_name")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("_valid_table_name")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("validTableName")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("valid123")).doesNotThrowAnyException();
    }

    @Test
    public void testCheckIsValidTableOrColumnName_InvalidNonQualifiedNames() {
        // Invalid non-qualified table/column names (pattern doesn't match)
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("123invalid"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name"); // Starts with a number
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("invalid-name"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name"); // Contains a hyphen
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("invalid..name"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Contains two dots, not a valid qualified name
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName(""))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("cannot be null or empty"); // Empty string
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName(null))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("cannot be null or empty"); // Null
    }

    @Test
    public void testCheckIsValidTableOrColumnName_ValidQualifiedNames() {
        // Valid qualified table/column names
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.my_table")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema_name.my_table_name")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("_schema._my_table")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema123.my_table123")).doesNotThrowAnyException();
    }

    @Test
    public void testCheckIsValidTableOrColumnName_InvalidQualifiedNames() {
        // Invalid qualified table/column names (pattern doesn't match)
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("123schema.table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Schema starts with a number
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.123table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Table starts with a number
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema-name.table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Schema contains a hyphen
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.table-name"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Table contains a hyphen
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema..table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Contains two dots
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.table."))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name"); // Ends with a dot
    }

    @Test
    public void testCheckIsValidTableOrColumnName_ReservedKeywords() {
        // Non-qualified table/column names that are reserved keywords
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("SELECT"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("FROM"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("WHERE"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("TABLE"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("FUNCTION"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
    }

    @Test
    public void testCheckIsValidTableOrColumnName_QualifiedNamesWithReservedKeywords() {
        // Qualified table/column names where one part is a reserved keyword
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("SELECT.table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.SELECT"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("FROM.table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.FROM"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("TABLE.table"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName("schema.TABLE"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");
    }

    @Test
    public void testCheckIsValidTableOrColumnName_TotalLengthLimits() {
        // Test that table/column name validation includes total length checking
        String longButValidPart = "a".repeat(60);
        String qualifiedTableName = longButValidPart + "." + longButValidPart; // 121 chars total
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName(qualifiedTableName)).doesNotThrowAnyException();

        // Test table/column name that exceeds total length limit
        String tooLongPart = "a".repeat(64);
        String tooLongQualifiedTableName = tooLongPart + "." + tooLongPart; // 129 chars total
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName(tooLongQualifiedTableName))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid qualified table or column name");

        // Test edge case: exactly at the limit for qualified names
        String maxLengthQualified = "a".repeat(63) + "." + "a".repeat(63); // 127 chars total
        assertThatCode(() -> PostgresqlUtil.checkIsValidTableOrColumnName(maxLengthQualified)).doesNotThrowAnyException();

        // Test single identifier length limit
        String tooLongSingleIdentifier = "a".repeat(PostgresqlUtil.MAX_IDENTIFIER_LENGTH + 1);
        assertThatThrownBy(() -> PostgresqlUtil.checkIsValidTableOrColumnName(tooLongSingleIdentifier))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
    }

}
