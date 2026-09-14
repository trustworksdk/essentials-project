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
 * Handles an unordered message: its identity, its bytes, and the {@code payloadType} it was enqueued
 * with.
 * <p>
 * A dedicated interface rather than {@code Consumer<byte[]>}, because the type has to reach the
 * handler for the column to be worth storing. It was written on every row, carried through
 * dead-lettering and returned on the pull and dead-letter paths — but the push path, which is how
 * almost everything consumes, never saw it. A consumer therefore had to recover the type from inside
 * the payload, at which point the column bought nothing.
 * <p>
 * {@link MessageId} is here for the same reason and costs nothing to supply: the owner already knows
 * its own lane and shard, and {@code seq} is already on the row the cursor read returns. See
 * {@link dk.trustworks.essentials.components.queue.shardowned.spi.MessageHandler} for what is
 * deliberately still absent and why.
 */
@FunctionalInterface
public interface PayloadHandler {
    void handle(MessageId messageId, byte[] payload, int payloadType);
}
