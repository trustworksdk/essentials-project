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
import org.jdbi.v3.core.mapper.*;

import java.lang.reflect.Type;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;

public class JavaTimeColumnMapperFactory implements ColumnMapperFactory {
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

   @Override
     public Optional<ColumnMapper<?>> build(Type type, ConfigRegistry config) {
       if (type == LocalDate.class) {
          return Optional.of((ColumnMapper<LocalDate>) (rs, column, ctx) -> {
               Date d = rs.getDate(column);
               return d == null ? null : d.toLocalDate();
             });
       }

       if (type == LocalTime.class) {
         return Optional.of((ColumnMapper<LocalTime>) (rs, column, ctx) -> {
           Time t = rs.getTime(column);
           return t == null ? null : t.toLocalTime();
         });
       }

       if (type == LocalDateTime.class) {
         return Optional.of((ColumnMapper<LocalDateTime>) (rs, column, ctx) -> {
           Timestamp ts = rs.getTimestamp(column, UTC); // interpret DATETIME2 as UTC
           return ts == null ? null : ts.toLocalDateTime();
         });
       }

       if (type == Instant.class) {
         return Optional.of((ColumnMapper<Instant>) (rs, column, ctx) -> {
           Timestamp ts = rs.getTimestamp(column, UTC);
           return ts == null ? null : ts.toInstant();
         });
       }

       if (type == OffsetDateTime.class) {
           return Optional.of((ColumnMapper<OffsetDateTime>) (rs, column, ctx) -> {
               Timestamp ts = rs.getTimestamp(column, UTC);
               return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
           });
       }

       if (type == ZonedDateTime.class) {
           return Optional.of((ColumnMapper<ZonedDateTime>) (rs, column, ctx) -> {
               Timestamp ts = rs.getTimestamp(column, UTC);
               return ts == null ? null : ts.toInstant().atZone(ZoneOffset.UTC);
           });
       }

       return Optional.empty();
    }
}
