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
 *
 * <h2>What a handler is given, and what it is not</h2>
 * A handler receives the {@link MessageId}, the ordering key, the payload and the {@code payloadType}.
 * It does <b>not</b> receive the delivery attempt count, the enqueue or next-delivery timestamps, or
 * the last delivery error, and that is a deliberate boundary rather than an oversight.
 * <p>
 * The reason is the cursor read. {@code messageId} is free: the owner already knows its own lane and
 * shard, and {@code seq} is already a column of the row the cursor returns, so passing it adds nothing
 * to the hot path. Every other field would. {@code attempts}, {@code enqueued_at}, {@code visible_at}
 * and {@code last_error} all exist on the row in the database, but none is in the {@code SELECT} the
 * delivery path issues — adding them widens the read that runs roughly twice per delivered message,
 * for data the overwhelming majority of handlers never look at.
 * <p>
 * A handler that genuinely needs them can ask for them by id, which is precisely what the id is for:
 * {@link MessageQueue#getMessage(MessageId)} reads the full row. That turns a per-message cost paid by
 * everyone into a per-lookup cost paid by the handler that wants it.
 * <p>
 * <b>Through the {@code DurableQueues} adapter this surfaces as a throw, not a stub.</b>
 * {@code QueuedMessage.getTotalDeliveryAttempts()} and the timestamp accessors raise
 * {@code UnsupportedOperationException} on the push path rather than returning a plausible-looking
 * zero — retry logic keyed on an attempt count that is always {@code 0} would silently never fire.
 * {@code getId()} is answered, because of the above.
 */
@FunctionalInterface
public interface MessageHandler {
    /**
     * @param messageId   which message this is — {@code (lane, shard, sequence)}, unique within this
     *                    queue. See the class javadoc for what a handler is NOT given
     * @param key         the ordering key for an ordered message, null otherwise
     * @param payload     the bytes as enqueued; this engine never looks inside them
     * @param payloadType the discriminator supplied at enqueue. Opaque to the engine — it is stored,
     *                    carried through dead-lettering and handed back here, and nothing else. What
     *                    the number means is entirely the application's contract; see
     *                    {@link Message#payloadType()}
     */
    void handle(MessageId messageId, String key, byte[] payload, int payloadType) throws Exception;
}
