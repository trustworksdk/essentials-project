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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link QueuedMessage} over a shard-owned message.
 *
 * <h2>Two shapes, and the difference is not cosmetic</h2>
 * Read from the database — {@code getQueuedMessage}, {@code getDeadLetterMessages}, the pull path —
 * every field is real, because the engine's row carries them.
 * <p>
 * Handed to a push consumer, it is <b>partial</b> — but less so than it was, and the line now falls
 * where the cost is rather than where the plumbing happened to stop.
 * <p>
 * <b>The id is answered on both shapes.</b> The engine's {@code MessageHandler} receives the
 * {@link dk.trustworks.essentials.components.queue.shardowned.spi.MessageId}, which costs the delivery
 * path nothing: an owner already knows its own lane and shard, and {@code seq} is already a column of
 * the row the cursor read returns. {@link QueueEntryIdCodec} pairs it with the queue name, and
 * {@link #getId()} hands it over.
 * <p>
 * <b>The attempt count, the timestamps and the last delivery error are not</b>, and those are the ones
 * with a real price. {@code attempts}, {@code enqueued_at}, {@code visible_at} and {@code last_error}
 * are all on the row in the database, but none is in the {@code SELECT} the delivery path issues —
 * adding them widens a read that runs roughly twice per delivered message, for data most handlers never
 * touch. A handler that needs them can pay for them one at a time: {@link #getId()} is now available to
 * pass to {@code getQueuedMessage(queueEntryId)}.
 * <p>
 * What is absent is dealt with by <b>throwing rather than inventing</b>. A
 * {@code getTotalDeliveryAttempts()} of {@code 0} would be a plausible-looking lie: retry logic keyed
 * on the attempt count would silently never trigger, and a dashboard would show every message as a
 * first attempt. An {@link UnsupportedOperationException} naming the reason is worse to hit and better
 * to have.
 * <p>
 * {@code Inbox}, {@code Outbox} and {@code DurableLocalCommandBus} touch only {@link #getMessage()} and
 * {@link #getMetaData()} on this path, which is why they work unchanged.
 */
public final class ShardOwnedQueuedMessage implements QueuedMessage {

    private final QueueEntryId   id;
    private final QueueName      queueName;
    private final Message        message;
    private final int            totalDeliveryAttempts;
    private final OffsetDateTime addedTimestamp;
    private final OffsetDateTime nextDeliveryTimestamp;
    private final boolean        deadLetterMessage;
    private final String         lastDeliveryError;
    /**
     * Set only on the push path; every accessor the engine cannot answer there consults it.
     */
    private final boolean        partial;

    private volatile Duration manualRedeliveryDelay;

    private ShardOwnedQueuedMessage(QueueEntryId id,
                                    QueueName queueName,
                                    Message message,
                                    int totalDeliveryAttempts,
                                    OffsetDateTime addedTimestamp,
                                    OffsetDateTime nextDeliveryTimestamp,
                                    boolean deadLetterMessage,
                                    String lastDeliveryError,
                                    boolean partial) {
        this.id = id;
        this.queueName = requireNonNull(queueName, "No queueName provided");
        this.message = requireNonNull(message, "No message provided");
        this.totalDeliveryAttempts = totalDeliveryAttempts;
        this.addedTimestamp = addedTimestamp;
        this.nextDeliveryTimestamp = nextDeliveryTimestamp;
        this.deadLetterMessage = deadLetterMessage;
        this.lastDeliveryError = lastDeliveryError;
        this.partial = partial;
    }

    /**
     * Fully populated, from a row the adapter read itself.
     */
    public static ShardOwnedQueuedMessage read(QueueEntryId id,
                                               QueueName queueName,
                                               Message message,
                                               int totalDeliveryAttempts,
                                               Instant addedAt,
                                               Instant nextDeliveryAt,
                                               boolean deadLetterMessage,
                                               String lastDeliveryError) {
        requireNonNull(id, "No id provided");
        return new ShardOwnedQueuedMessage(id, queueName, message, totalDeliveryAttempts,
                                           atOffset(addedAt), atOffset(nextDeliveryAt),
                                           deadLetterMessage, lastDeliveryError, false);
    }

    /**
     * What a push consumer's handler is given: the message, its metadata and its id, and nothing the
     * engine does not hand to a {@code MessageHandler}.
     * <p>
     * The id is real here — it costs the delivery path nothing, because the owner already knows its own
     * lane and shard and {@code seq} is already a column of the row the cursor read returns. The
     * attempt count and the timestamps are the ones that would widen that read, and they remain
     * {@link #unavailable}.
     */
    public static ShardOwnedQueuedMessage beingDelivered(QueueName queueName, QueueEntryId id, Message message) {
        requireNonNull(id, "No id provided");
        return new ShardOwnedQueuedMessage(id, queueName, message, 0, null, null, false, null, true);
    }

    private static OffsetDateTime atOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private <T> T unavailable(String what) {
        throw new UnsupportedOperationException(
                what + " is not available to a handler on the shard-owned engine's push delivery path. "
                        + "The engine's MessageHandler receives (messageId, key, payload, payloadType) only - the value "
                        + "exists on the row but is not in the SELECT the delivery path issues, and adding it would widen "
                        + "a read that runs roughly twice per delivered message. getId() IS available here: pass it to "
                        + "getQueuedMessage(queueEntryId) to read the full row when you need this. See this module's "
                        + "CLAUDE.md for why it is not simply added.");
    }

    @Override
    public QueueEntryId getId() {
        return id;
    }

    @Override
    public QueueName getQueueName() {
        return queueName;
    }

    @Override
    public Message getMessage() {
        return message;
    }

    @Override
    public OffsetDateTime getAddedTimestamp() {
        return partial ? unavailable("The added timestamp") : addedTimestamp;
    }

    @Override
    public OffsetDateTime getNextDeliveryTimestamp() {
        return partial ? unavailable("The next delivery timestamp") : nextDeliveryTimestamp;
    }

    @Override
    public OffsetDateTime getDeliveryTimestamp() {
        return partial ? unavailable("The delivery timestamp") : null;
    }

    @Override
    public String getLastDeliveryError() {
        return partial ? unavailable("The last delivery error") : lastDeliveryError;
    }

    @Override
    public boolean isDeadLetterMessage() {
        return deadLetterMessage;
    }

    @Override
    public int getTotalDeliveryAttempts() {
        return partial ? unavailable("The delivery attempt count") : totalDeliveryAttempts;
    }

    @Override
    public int getRedeliveryAttempts() {
        return partial ? unavailable("The redelivery attempt count") : Math.max(0, totalDeliveryAttempts - 1);
    }

    @Override
    public DeliveryMode getDeliveryMode() {
        return message instanceof OrderedMessage ? DeliveryMode.IN_ORDER : DeliveryMode.NORMAL;
    }

    @Override
    public boolean isBeingDelivered() {
        return partial;
    }

    /**
     * Records a handler's request to be redelivered later.
     *
     * <h2>The delay is not honoured on the push path, and that is a deviation worth stating</h2>
     * The adapter reads this flag after the handler returns and, if set, fails the delivery so the
     * engine reschedules it. The engine then applies <em>its</em> backoff from the redelivery policy —
     * it has no way to accept a per-message delay from inside a handler, because the handler is never
     * told which message it is holding, so there is no id to schedule against.
     * <p>
     * So the message <em>is</em> redelivered, at the policy's next interval rather than at
     * {@code deliveryDelay}, and the attempt counts against the policy's budget. A handler that needs
     * an exact delay has to use {@code retryMessage(queueEntryId, …)} from the pull path, where the id
     * is known.
     */
    @Override
    public void markForRedeliveryIn(Duration deliveryDelay) {
        this.manualRedeliveryDelay = requireNonNull(deliveryDelay, "No deliveryDelay provided");
    }

    @Override
    public boolean isManuallyMarkedForRedelivery() {
        return manualRedeliveryDelay != null;
    }

    @Override
    public Duration getRedeliveryDelay() {
        return manualRedeliveryDelay;
    }

    @Override
    public String toString() {
        return "ShardOwnedQueuedMessage{queueName=" + queueName
                + ", id=" + id
                + ", partial=" + partial
                + ", deliveryMode=" + getDeliveryMode() + "}";
    }
}
