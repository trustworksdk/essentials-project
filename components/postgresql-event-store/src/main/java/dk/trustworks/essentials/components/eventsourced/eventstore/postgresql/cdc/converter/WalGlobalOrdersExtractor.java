/*
 *  Copyright 2021-2026 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * WalGlobalOrdersExtractor is an interface designed for extracting {@code Gap} objects
 * from a given logical replication message in JSON format.
 * This interface supports multiple methods to handle input data, allowing implementations
 * to parse and interpret the message and extract relevant details about global event ordering.
 */
public interface WalGlobalOrdersExtractor {
    List<Gap> extract(String wal2jsonMessage);

    default List<Gap> extract(byte[] wal2jsonMessageBytes) {
        if (wal2jsonMessageBytes == null || wal2jsonMessageBytes.length == 0) return List.of();
        return extract(new String(wal2jsonMessageBytes, StandardCharsets.UTF_8));
    }

    record Gap(AggregateType aggregateType, GlobalEventOrder globalEventOrder) {}
}
