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

package dk.trustworks.essentials.components.queue.jdbc;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;

public final class JdbcMessageMappingSupport {
    private JdbcMessageMappingSupport() {
    }

    public static JdbcMessageMappingResult<JdbcMessageMappingResult.FailedMessageMapping> mapQueryResultsWithExceptionHandling(Query query,
                                                                                                                              RowMapper<QueuedMessage> queuedMessageMapper) {
        List<QueuedMessage> successfulMessages = new ArrayList<>();
        List<JdbcMessageMappingResult.FailedMessageMapping> failedMappings = new ArrayList<>();

        var customMapper = new RowMapper<QueuedMessage>() {
            @Override
            public QueuedMessage map(ResultSet rs, StatementContext ctx) throws SQLException {
                try {
                    return queuedMessageMapper.map(rs, ctx);
                } catch (Exception e) {
                    QueueName queueName = QueueName.of(rs.getString("queue_name"));
                    QueueEntryId queueEntryId = QueueEntryId.of(rs.getString("id"));
                    failedMappings.add(new JdbcMessageMappingResult.FailedMessageMapping(queueName, queueEntryId, e));
                    return null;
                }
            }
        };

        List<QueuedMessage> allResults = query.map(customMapper).list();
        successfulMessages.addAll(allResults.stream().filter(Objects::nonNull).toList());

        return new JdbcMessageMappingResult<>(successfulMessages, failedMappings);
    }

    public static <F extends JdbcMessageMappingResult.FailedMessageMapping, R> R mapQueryResultsWithExceptionHandling(Query query,
                                                                                                                        RowMapper<QueuedMessage> queuedMessageMapper,
                                                                                                                        Function<JdbcMessageMappingResult.FailedMessageMapping, F> failedMappingConverter,
                                                                                                                        BiFunction<List<QueuedMessage>, List<F>, R> resultFactory) {
        var jdbcResult = mapQueryResultsWithExceptionHandling(query, queuedMessageMapper);
        var failedMappings = jdbcResult.failedMappings().stream()
                                       .map(failedMappingConverter)
                                       .toList();
        return resultFactory.apply(jdbcResult.successfulMessages(), failedMappings);
    }
}
