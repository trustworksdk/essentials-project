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

import java.util.function.Function;
import java.util.regex.Pattern;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Represents an argument with a specific type, value, and serialization logic that is primarily
 * used for SQL queries or other structured data contexts.
 *
 * @param <T> the datatype of the argument's value
 */
public class Arg<T> {
    protected static final int     MAX_ARGS_COUNT = 16;
    private static final   int     MAX_ARG_LENGTH = 255;
    /**
     * This pattern ensures that the argument string adheres to the following criteria:
     * <ul>
     *     <li>Does not contain the substring "--" to prevent SQL comments or injections.</li>
     *     <li>Can contain alphanumeric characters (A-Z, a-z, 0-9).</li>
     *     <li>Can include special characters such as dots (.), underscores (_), dashes (-),
     *         spaces, angle brackets (<, >), equal signs (=), parentheses (), commas (,), and single quotes (').</li>
     * </ul>
     * This validation aims to enforce a controlled format to avoid invalid or potentially
     * harmful SQL inputs.
     */
    private static final   Pattern ARG_PATTERN    = Pattern.compile("^(?!.*--)[A-Za-z0-9_.\\-\\s<>=(),']+$");

    private final T                   value;
    public final  ArgType             type;
    private final Function<T, String> serializer;

    private Arg(ArgType type, T value, Function<T, String> serializer) {
        this.type = type;
        this.value = value;
        this.serializer = serializer;
    }

    /**
     * Creates a new argument for a SQL identifier. The provided identifier is validated
     * to ensure it complies with the rules for table or column names in SQL.
     *
     * @param name the name of the identifier. It must not be null and must conform to SQL identifier
     *             naming conventions.<br>
     *             <strong>Security Note:</strong><br>
     *             <b>It is the responsibility of the user of this component to sanitize the {@code name} value to ensure the security of all the SQL statements where this identifier is part of the generated SQL.</b><br>
     *             The {@link Arg} will attempt to validate {@code name} using {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} as an initial layer
     *             of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
     *             However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     *             <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     *             Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *             Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     *             <br>
     *             It is highly recommended that the {@code name} value is only derived from a controlled and trusted source.<br>
     *             To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code name} value.
     * @return an {@code Arg<String>} instance representing the provided identifier, ready for use in
     * SQL statements.
     * @throws IllegalArgumentException if the provided name is null or if the name does not meet the SQL identifier naming requirements.
     */
    public static Arg<String> identifier(String name) {
        requireNonNull(name, "name cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(name);
        return new Arg<>(ArgType.IDENTIFIER, name, Arg::quoteIdentifier);
    }


    /**
     * Creates a new argument representing a SQL literal value. The literal is validated for length and syntax
     * to ensure it complies with constraints and patterns.
     * <p><strong>Examples of valid literals:</strong></p>
     * <ul>
     *   <li><code>user_name</code></li>
     *   <li><code>schema.table</code></li>
     *   <li><code>value = 100</code></li>
     *   <li><code>(col1, col2)</code></li>
     *   <li><code>'hello world'</code></li>
     *   <li><code>&lt;= 50</code></li>
     * </ul>
     *
     * @param literal the string value to be used as a SQL literal. It must not be null, cannot exceed a predefined
     *                maximum length, and must match a specific pattern.
     *                <strong>Security Note:</strong><br>
     *                <b>It is the responsibility of the user of this component to sanitize the {@code literal} value to ensure the security of all the SQL statements where this identifier is part of the generated SQL.</b><br>
     *                The {@link Arg} will attempt to validate {@code literal} using {@link #ARG_PATTERN} as an initial layer
     *                of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
     *                However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     *                <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     *                Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *                Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     *                <br>
     *                It is highly recommended that the {@code literal} value is only derived from a controlled and trusted source.<br>
     *                To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code literal} value.
     * @return an {@code Arg<String>} instance representing the provided literal value, ready for use in SQL statements.
     * @throws IllegalArgumentException if the literal is null, exceeds the maximum allowed length, or does not match
     *                                  the expected syntax pattern.
     */
    public static Arg<String> literal(String literal) {
        requireNonNull(literal, "literal cannot be null");

        if (literal.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException(
                    msg("Literal too long ('{}' chars), max is '{}'",
                        literal.length(), MAX_ARG_LENGTH)
            );
        }
        if (!ARG_PATTERN.matcher(literal).matches()) {
            throw new IllegalArgumentException(
                    msg("Invalid literal syntax: '{}'", literal)
            );
        }

        return new Arg<>(ArgType.LITERAL, literal, Arg::quoteLiteral);
    }

    /**
     * Creates an argument representing a SQL table name as a literal.<br>
     * The provided table name is validated to ensure it adheres to SQL table naming conventions
     * and is safe for use in SQL statements.
     *
     * @param table the name of the table to be used as a SQL literal. It must not be null
     *              and must comply with SQL table naming rules.
     *              <strong>Security Note:</strong><br>
     *              <b>It is the responsibility of the user of this component to sanitize the {@code table} value to ensure the security of all the SQL statements where this identifier is part of the generated SQL.</b><br>
     *              The {@link Arg} will attempt to validate {@code table} using {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} as an initial layer
     *              of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
     *              However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     *              <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     *              Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *              Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     *              <br>
     *              It is highly recommended that the {@code table} value is only derived from a controlled and trusted source.<br>
     *              To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code table} value.
     * @return an {@code Arg<String>} instance representing the provided table name as a literal.
     * @throws IllegalArgumentException if the table name is null or if the table name does not meet SQL table naming requirements.
     */
    public static Arg<String> tableNameLiteral(String table) {
        requireNonNull(table, "table name must not be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(table);
        return new Arg<>(ArgType.LITERAL, table, Arg::quoteLiteral);
    }

    /**
     * Creates a new argument representing a typed SQL expression. The expression
     * is serialized into a string form using the provided serializer function.<br>
     * No further validation is performed on the expression!
     *
     * @param <T>        the type of the expression
     * @param expression the expression to be serialized. It must not be null.
     *                   <strong>Security Note:</strong><br>
     *                   <b>It is the responsibility of the user of this component to sanitize the {@code expression} value to ensure the security of all the SQL statements where this identifier is part of the generated SQL.</b><br>
     *                   The {@link Arg} will NOT attempt to validate {@code expression}<br>
     *                   <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     *                   Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *                   Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     *                   <br>
     *                   It is highly recommended that the {@code expression} value is only derived from a controlled and trusted source.<br>
     *                   To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code expression} value.
     * @param serializer the function used to serialize the expression into a string
     *                   representation. It must not be null.
     * @return an {@code Arg<T>} instance representing the serialized SQL expression.
     * @throws IllegalArgumentException if the {@code expression} or {@code serializer} is null.
     */
    public static <T> Arg<T> expression(T expression, Function<T, String> serializer) {
        requireNonNull(expression, "expression cannot be null");
        requireNonNull(serializer, "serializer cannot be null");
        return new Arg<>(ArgType.EXPRESSION, expression, serializer);
    }

    /**
     * Converts the value of this argument into its SQL string representation
     * using the provided serializer function.
     *
     * @return the SQL string representation of the argument value.
     */
    public String toSql() {
        return serializer.apply(value);
    }

    private static String quoteIdentifier(String in) {
        return "\"" + in.replace("\"", "\"\"") + "\"";
    }

    private static String quoteLiteral(String in) {
        return "'" + in.replace("'", "''") + "'";
    }

    @Override
    public String toString() {
        return "Arg{" +
                "type=" + type + ", " +
                "value=" + toSql() +
                '}';
    }
}
