/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.types.jdbi.mssql;

import org.jdbi.v3.core.argument.*;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.lang.reflect.Type;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;

public class JavaTimeArgumentFactory implements ArgumentFactory {
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    @Override
    public Optional<Argument> build(Type type, Object value, ConfigRegistry config) {
        if (value == null) return Optional.empty(); // let caller handle null

        if (value instanceof LocalDate ld) {
            Date d = Date.valueOf(ld);
            return Optional.of((pos, ps, ctx) -> ps.setDate(pos, d));
        }
        if (value instanceof LocalTime lt) {
            Time t = Time.valueOf(lt);
            return Optional.of((pos, ps, ctx) -> ps.setTime(pos, t));
        }
        if (value instanceof LocalDateTime ldt) {
            Timestamp ts = Timestamp.valueOf(ldt);
            return Optional.of((pos, ps, ctx) -> ps.setTimestamp(pos, ts, UTC));
        }
        if (value instanceof Instant i) {
            Timestamp ts = Timestamp.from(i);
            return Optional.of((pos, ps, ctx) -> ps.setTimestamp(pos, ts, UTC));
        }
        if (value instanceof OffsetDateTime odt) {
            LocalDateTime ldt = odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            Timestamp ts = Timestamp.valueOf(ldt);
            return Optional.of((pos, ps, ctx) -> ps.setTimestamp(pos, ts, UTC));
        }
        if (value instanceof ZonedDateTime zdt) {
            LocalDateTime ldt = zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            Timestamp ts = Timestamp.valueOf(ldt);
            return Optional.of((pos, ps, ctx) -> ps.setTimestamp(pos, ts, UTC));
        }
        return Optional.empty();
    }
}
