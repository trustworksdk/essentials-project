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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest;

import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;

/**
 * Renders a shard-owned {@link QueueName} as a JSON string rather than as an object wrapping its
 * {@code value} field.
 *
 * <h2>Why this is needed here at all</h2>
 * {@code AdminApiJacksonModule} covers the foundation's value types by registering a serializer for
 * {@link dk.trustworks.essentials.types.CharSequenceType}. This engine's {@code QueueName} is
 * deliberately <em>not</em> one — {@code types} carries kotlin-reflect and kotlin-stdlib, which a
 * module depending on {@code shared} alone does not want — so it is a plain record, and a plain record
 * serialises as {@code {"value":"orders"}}. That is unreadable for a client and, worse, differs from
 * how the same concept renders on the durable-queues endpoints.
 * <p>
 * The annotation-based fix ({@code @JsonValue}) is not available: it would put a Jackson dependency on
 * the engine module, whose dependency list is the reason {@code QueueName} is a record in the first
 * place. So the mapping lives in the HTTP layer, which is where the wire format is decided anyway.
 * <p>
 * Serializer only — no request body carries a queue name. Every endpoint takes it as a path segment,
 * which arrives as a {@link String}.
 */
public class ShardOwnedQueueJacksonModule extends SimpleModule {

    public ShardOwnedQueueJacksonModule() {
        super("essentials-shard-owned-queue");
        addSerializer(QueueName.class, new QueueNameSerializer());
    }

    private static final class QueueNameSerializer extends ValueSerializer<QueueName> {
        @Override
        public void serialize(QueueName value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.value());
        }
    }
}
