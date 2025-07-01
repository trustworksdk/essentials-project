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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.function.Function;
import java.util.regex.Pattern;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class Arg<T> {
    protected static final int     MAX_ARGS_COUNT = 16;
    private static final   int     MAX_ARG_LENGTH = 255;
    private static final   Pattern ARG_PATTERN    =
            Pattern.compile("[A-Za-z0-9_.\\-\\s<>=(),']+");

    private final T                   value;
    public final  ArgType             type;
    private final Function<T, String> serializer;

    private Arg(ArgType type, T value, Function<T, String> serializer) {
        this.type = type;
        this.value = value;
        this.serializer = serializer;
    }

    public static Arg<String> identifier(String name) {
        requireNonNull(name, "name cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(name);
        return new Arg<>(ArgType.IDENTIFIER, name, Arg::quoteIdentifier);
    }

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

    public static Arg<String> tableNameLiteral(String table) {
        requireNonNull(table, "table name must not be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(table);
        return new Arg<>(ArgType.LITERAL, table, Arg::quoteLiteral);
    }

    public static <T> Arg<T> expression(T expr, Function<T, String> serializer) {
        requireNonNull(expr, "expr cannot be null");
        requireNonNull(serializer, "serializer cannot be null");
        return new Arg<>(ArgType.EXPRESSION, expr, serializer);
    }

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
