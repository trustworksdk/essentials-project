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

package dk.trustworks.essentials.components.queue.shardowned.spi;

/**
 * Handles one delivered message.
 * <p>
 * Throwing signals failure and triggers the redelivery policy. Returning normally is an
 * acknowledgement — with one exception the engine enforces rather than documents: a handler that
 * returns while its thread is interrupted is NOT treated as successful, because a handler that
 * catches {@code InterruptedException} and returns is indistinguishable from one that finished, and
 * treating them alike silently loses messages on every graceful shutdown.
 */
@FunctionalInterface
public interface MessageHandler {
    /**
     * @param key         the ordering key for an ordered message, null otherwise
     * @param payload     the bytes as enqueued; this engine never looks inside them
     * @param payloadType the discriminator supplied at enqueue. Opaque to the engine — it is stored,
     *                    carried through dead-lettering and handed back here, and nothing else. What
     *                    the number means is entirely the application's contract; see
     *                    {@link Message#payloadType()}
     */
    void handle(String key, byte[] payload, int payloadType) throws Exception;
}
