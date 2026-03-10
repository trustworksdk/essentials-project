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
package dk.trustworks.essentials.components.queue.mssql;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.functional.*;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.*;
import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class QueuedMessageRowMapper implements RowMapper<QueuedMessage> {
    private final TripleFunction<QueueName, QueueEntryId, String, MessageMetaData> metadataDeserializer;
    private final QuadFunction<QueueName, QueueEntryId, String, String, Object>    payloadDeserializer;

    public QueuedMessageRowMapper(QuadFunction<QueueName, QueueEntryId, String, String, Object> payloadDeserializer,
                                  TripleFunction<QueueName, QueueEntryId, String, MessageMetaData> metadataDeserializer) {
        this.payloadDeserializer = requireNonNull(payloadDeserializer, "No payloadDeserializer provided");
        this.metadataDeserializer = requireNonNull(metadataDeserializer, "No metadataDeserializer provided");
    }

    @Override
    public QueuedMessage map(ResultSet rs, StatementContext ctx) throws SQLException {
        var queueName      = QueueName.of(rs.getString("queue_name"));
        var queueEntryId   = QueueEntryId.of(rs.getString("id"));
        var messagePayload = payloadDeserializer.apply(queueName, queueEntryId, rs.getString("message_payload"), rs.getString("message_payload_type"));

        MessageMetaData messageMetaData;
        var metaDataColumnValue = rs.getString("meta_data");
        if (metaDataColumnValue != null) {
            messageMetaData = metadataDeserializer.apply(queueName, queueEntryId, metaDataColumnValue);
        } else {
            messageMetaData = new MessageMetaData();
        }

        var deliveryMode = QueuedMessage.DeliveryMode.valueOf(rs.getString("delivery_mode"));
        Message message;
        switch (deliveryMode) {
            case NORMAL:
                message = new Message(messagePayload, messageMetaData);
                break;
            case IN_ORDER:
                message = new OrderedMessage(messagePayload,
                                             rs.getString("key"),
                                             rs.getLong("key_order"),
                                             messageMetaData);
                break;
            default:
                throw new IllegalStateException(msg("Unsupported deliveryMode '{}'", deliveryMode));
        }

        return new DefaultQueuedMessage(queueEntryId,
                                        queueName,
                                        message,
                                        normalizeToUtc(rs.getObject("added_ts", OffsetDateTime.class)),
                                        normalizeToUtc(rs.getObject("next_delivery_ts", OffsetDateTime.class)),
                                        normalizeToUtc(rs.getObject("delivery_ts", OffsetDateTime.class)),
                                        rs.getString("last_delivery_error"),
                                        rs.getInt("total_attempts"),
                                        rs.getInt("redelivery_attempts"),
                                        rs.getBoolean("is_dead_letter_message"),
                                        rs.getBoolean("is_being_delivered"));
    }

    private static OffsetDateTime normalizeToUtc(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }

        // SQL Server JDBC can return DATETIMEOFFSET values as UTC with local wall-clock time.
        // Reinterpret UTC-offset values in local zone and normalize back to UTC.
        var systemOffset = ZoneId.systemDefault().getRules().getOffset(Instant.now());
        if (dateTime.getOffset().equals(ZoneOffset.UTC) && !systemOffset.equals(ZoneOffset.UTC)) {
            return dateTime.toLocalDateTime()
                           .atZone(ZoneId.systemDefault())
                           .toOffsetDateTime()
                           .withOffsetSameInstant(ZoneOffset.UTC);
        }

        return dateTime.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
