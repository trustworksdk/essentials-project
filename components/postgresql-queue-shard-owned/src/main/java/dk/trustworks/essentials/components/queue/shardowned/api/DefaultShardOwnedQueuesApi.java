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

package dk.trustworks.essentials.components.queue.shardowned.api;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.*;

/**
 * {@link ShardOwnedQueuesApi} over a {@link MessageQueues} registry.
 * <p>
 * Every method here does the same four things and nothing else: authorise, resolve the queue name,
 * delegate to {@link MessageQueue}, convert. It contains no queue logic, which is the point — an
 * administrative path that reimplements any part of the engine is a second implementation to keep
 * correct, and the first place a divergence would show is on the rarely-exercised path.
 */
public class DefaultShardOwnedQueuesApi implements ShardOwnedQueuesApi {

    /**
     * The largest dead-letter page this will serve.
     * <p>
     * A dead-letter table is unbounded and its rows carry payloads. An un-capped {@code limit} turns
     * one request into an arbitrary amount of memory on both sides, so an over-large ask is clamped
     * rather than refused — an operator paging through a backlog should not have to guess the limit.
     */
    public static final int MAX_PAGE_SIZE = 1_000;

    private final EssentialsSecurityProvider securityProvider;
    private final MessageQueues              queues;

    public DefaultShardOwnedQueuesApi(EssentialsSecurityProvider securityProvider, MessageQueues queues) {
        this.securityProvider = requireNonNull(securityProvider, "No securityProvider provided");
        this.queues = requireNonNull(queues, "No queues provided");
    }

    @Override
    public List<QueueName> getQueueNames(Object principal) {
        validateQueueReaderRole(principal);
        return call("list queue names", queues::queueNames);
    }

    @Override
    public Optional<ApiShardOwnedQueueStatus> getQueueStatus(Object principal, QueueName queueName) {
        validateQueueReaderRole(principal);
        requireNonNull(queueName, "No queueName provided");
        return call("read the status of queue '" + queueName.value() + "'",
                    () -> {
                        var queue = queues.findQueue(queueName);
                        if (queue.isEmpty()) {
                            return Optional.empty();
                        }
                        return Optional.of(ApiShardOwnedQueueStatus.from(queueName,
                                                                         queue.get().depth(),
                                                                         queue.get().health()));
                    });
    }

    @Override
    public Optional<ApiShardOwnedMessage> getMessage(Object principal, QueueName queueName, MessageId messageId) {
        validateQueueReaderRole(principal);
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(messageId, "No messageId provided");
        var includePayload = mayReadPayloads(principal);
        return call("read message '" + messageId + "' of queue '" + queueName.value() + "'",
                    () -> {
                        var queue = queues.findQueue(queueName);
                        if (queue.isEmpty()) {
                            return Optional.empty();
                        }
                        return queue.get().getMessage(messageId)
                                    .map(message -> ApiShardOwnedMessage.from(queueName, message, includePayload));
                    });
    }

    @Override
    public List<ApiShardOwnedMessage> getDeadLetterMessages(Object principal, QueueName queueName, int offset, int limit) {
        validateQueueReaderRole(principal);
        requireNonNull(queueName, "No queueName provided");
        requireTrue(offset >= 0, "offset must not be negative");
        requireTrue(limit > 0, "limit must be positive");
        var includePayload = mayReadPayloads(principal);
        var pageSize       = Math.min(limit, MAX_PAGE_SIZE);
        return call("read the dead letters of queue '" + queueName.value() + "'",
                    () -> {
                        var queue = queues.findQueue(queueName);
                        if (queue.isEmpty()) {
                            return List.<ApiShardOwnedMessage>of();
                        }
                        return queue.get().deadLetters(offset, pageSize).stream()
                                    .map(deadLetter -> ApiShardOwnedMessage.from(queueName, deadLetter, includePayload))
                                    .toList();
                    });
    }

    @Override
    public boolean deleteMessage(Object principal, QueueName queueName, MessageId messageId) {
        validateQueueWriterRole(principal);
        requireNonNull(messageId, "No messageId provided");
        return write("delete message '" + messageId + "'", queueName,
                     queue -> queue.deleteMessage(messageId));
    }

    @Override
    public boolean retryMessage(Object principal, QueueName queueName, MessageId messageId, Duration delay) {
        validateQueueWriterRole(principal);
        requireNonNull(messageId, "No messageId provided");
        requireNonNull(delay, "No delay provided");
        requireTrue(!delay.isNegative(), "delay must not be negative");
        return write("retry message '" + messageId + "'", queueName,
                     queue -> queue.retryMessage(messageId, delay));
    }

    @Override
    public boolean markAsDeadLetterMessage(Object principal, QueueName queueName, MessageId messageId, String reason) {
        validateQueueWriterRole(principal);
        requireNonNull(messageId, "No messageId provided");
        requireNonNull(reason, "No reason provided");
        return write("dead-letter message '" + messageId + "'", queueName,
                     queue -> queue.markAsDeadLetter(messageId, reason));
    }

    @Override
    public boolean resurrectDeadLetterMessage(Object principal, QueueName queueName, MessageId messageId) {
        validateQueueWriterRole(principal);
        requireNonNull(messageId, "No messageId provided");
        return write("resurrect message '" + messageId + "'", queueName,
                     queue -> queue.resurrect(messageId));
    }

    @Override
    public long purgeQueue(Object principal, QueueName queueName) {
        validateQueueWriterRole(principal);
        requireNonNull(queueName, "No queueName provided");
        return call("purge queue '" + queueName.value() + "'",
                    () -> {
                        var queue = queues.findQueue(queueName);
                        return queue.isPresent() ? queue.get().purge() : 0L;
                    });
    }

    private boolean write(String what, QueueName queueName, ThrowingFunction<MessageQueue, Boolean> operation) {
        requireNonNull(queueName, "No queueName provided");
        return call(what + " of queue '" + queueName.value() + "'",
                    () -> {
                        var queue = queues.findQueue(queueName);
                        return queue.isPresent() && operation.apply(queue.get());
                    });
    }

    private void validateQueueReaderRole(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, QUEUE_READER, ESSENTIALS_ADMIN);
    }

    private void validateQueueWriterRole(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, QUEUE_WRITER, ESSENTIALS_ADMIN);
    }

    /**
     * Whether the caller may see message contents.
     * <p>
     * Checked once per request and passed down rather than re-asked per message: the answer cannot
     * change within one response, and a security provider is free to make the check expensive.
     */
    private boolean mayReadPayloads(Object principal) {
        return hasAnyEssentialsSecurityRoles(securityProvider, principal, QUEUE_PAYLOAD_READER, ESSENTIALS_ADMIN);
    }

    /**
     * Runs {@code work}, converting a {@link SQLException} into a {@link MessageQueueException} whose
     * message names the operation that failed.
     * <p>
     * The naming is the reason this is not a bare try/catch at each call site: a stack trace from a
     * connection pool says which statement failed, never which queue the operator was looking at.
     */
    private static <T> T call(String what, ThrowingSupplier<T> work) {
        try {
            return work.get();
        } catch (SQLException e) {
            throw new MessageQueueException("Failed to " + what, e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
