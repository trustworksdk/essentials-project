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

package dk.trustworks.essentials.types.jdbi;

import dk.trustworks.essentials.shared.types.GenericType;
import dk.trustworks.essentials.types.*;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.*;
import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Generic {@link ColumnMapper} for {@link ZonedDateTimeType}'s<br>
 * <br>
 * Example of a concrete mapper:
 * <pre>{@code
 * public final class TransactionTimeColumnMapper extends ZonedDateTimeTypeColumnMapper<TransactionTime> {
 * }}</pre>
 *
 * @param <T> the concrete {@link ZonedDateTimeType} this instance is mapping
 */
public abstract class ZonedDateTimeTypeColumnMapper<T extends ZonedDateTimeType<T>> implements ColumnMapper<T> {
    private final Class<T> concreteType;

    @SuppressWarnings("unchecked")
    public ZonedDateTimeTypeColumnMapper() {
        concreteType = (Class<T>) GenericType.resolveGenericTypeOnSuperClass(this.getClass(),
                                                                             0);
    }

    public ZonedDateTimeTypeColumnMapper(Class<T> concreteType) {
        this.concreteType = requireNonNull(concreteType, "No concreteType provided");
    }

    @Override
    public T map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        var value = r.getTimestamp(columnNumber);
        return value == null ?
               null :
               SingleValueType.from(ZonedDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault()),
                                    concreteType);
    }
}
