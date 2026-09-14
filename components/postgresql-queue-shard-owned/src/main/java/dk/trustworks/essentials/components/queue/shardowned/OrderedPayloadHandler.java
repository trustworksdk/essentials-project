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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;

/**
 * Handles an ordered message: its identity, its key, its bytes, and the {@code payloadType} it was
 * enqueued with. See {@link PayloadHandler} for why the type and the id are parameters.
 */
@FunctionalInterface
public interface OrderedPayloadHandler {
    void handle(MessageId messageId, String key, byte[] payload, int payloadType);
}
