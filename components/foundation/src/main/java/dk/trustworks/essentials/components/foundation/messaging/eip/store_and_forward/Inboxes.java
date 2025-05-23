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

package dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageConsumptionMode.SingleGlobalConsumer;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link Inbox} supports the transactional Store and Forward pattern from Enterprise Integration Patterns supporting At-Least-Once delivery guarantee.<br>
 * The {@link Inbox} pattern is used to handle incoming messages from a message infrastructure (such as a Queue, Kafka, EventBus, etc.). <br>
 * The message is added to the {@link Inbox} in a transaction/{@link UnitOfWork} and afterward the message is Acknowledged (ACK) with the message infrastructure and the {@link UnitOfWork} is committed.<br>
 * If the ACK fails then the message infrastructure will attempt to redeliver the message to the {@link Inbox}, since the message infrastructure and the {@link Inbox}
 * don't share the same transactional resource. This means that messages received from the message infrastructure
 * can be added more than once to the {@link Inbox}.<br>
 * <br>
 * After the {@link UnitOfWork} has been committed, the messages will be asynchronously delivered to the message consumer (typically in a new {@link UnitOfWork} dependent on the underlying implementation - see {@link DurableQueueBasedInboxes}).<br>
 * The {@link Inbox} itself supports Message Redelivery in case the Message consumer experiences failures.<br>
 * This means that the Message consumer, registered with the {@link Inbox}, can and will receive Messages more than once and therefore its message handling has to be idempotent.
 * <p>
 * If you're working with {@link OrderedMessage}'s then the {@link Inbox} consumer must be configured
 * with {@link InboxConfig#getMessageConsumptionMode()} having value {@link MessageConsumptionMode#SingleGlobalConsumer}
 * in order to be able to guarantee that {@link OrderedMessage}'s are delivered in {@link OrderedMessage#getOrder()} per {@link OrderedMessage#getKey()}
 * across as many {@link InboxConfig#numberOfParallelMessageConsumers} as you wish to use.
 *
 * @see DurableQueueBasedInboxes
 */
public interface Inboxes {
    /**
     * Get an existing {@link Inbox} instance or create a new instance. If an existing {@link Inbox} with a matching {@link InboxName} is already
     * created then that instance is returned (irrespective of whether the redeliveryPolicy, etc. have the same values)<br>
     * Remember to call {@link Outbox#consume(Consumer)} to start consuming messages
     *
     * @param inboxConfig the inbox configuration
     * @return the {@link Inbox}
     */
    Inbox getOrCreateInbox(InboxConfig inboxConfig);

    /**
     * Get an existing {@link Inbox} instance or create a new instance. If an existing {@link Inbox} with a matching {@link InboxName} is already
     * created then that instance is returned (irrespective of whether the redeliveryPolicy, etc. have the same values)
     *
     * @param inboxConfig     the inbox configuration
     * @param messageConsumer the asynchronous message consumer. See {@link PatternMatchingMessageHandler}
     * @return the {@link Inbox}
     */
    Inbox getOrCreateInbox(InboxConfig inboxConfig,
                           Consumer<Message> messageConsumer);

    /**
     * Get an existing {@link Inbox} instance or create a new instance. If an existing {@link Inbox} with a matching {@link InboxName} is already
     * created then that instance is returned (irrespective of whether the redeliveryPolicy, etc. have the same values)
     *
     * @param inboxConfig the inbox configuration
     * @param forwardTo   forward messages to this command bus using {@link CommandBus#send(Object)}
     * @return the {@link Inbox}
     */
    default Inbox getOrCreateInbox(InboxConfig inboxConfig,
                                   CommandBus forwardTo) {
        requireNonNull(forwardTo, "No forwardTo command bus provided");
        return getOrCreateInbox(inboxConfig,
                                message -> forwardTo.send(message.getPayload()));
    }

    /**
     * Get all the {@link Inbox} instances managed by this {@link Inboxes} instance
     *
     * @return all the {@link Inbox} instances managed by this {@link Inboxes} instance
     */
    Collection<Inbox> getInboxes();

    /**
     * Create an {@link Inboxes} instance that uses a {@link DurableQueues} as its storage and message delivery mechanism.
     *
     * @param durableQueues     The {@link DurableQueues} implementation used by the {@link Inboxes} instance returned
     * @param fencedLockManager the {@link FencedLockManager} used for {@link Inbox}'s that use {@link MessageConsumptionMode#SingleGlobalConsumer}
     * @return the {@link Inboxes} instance
     */
    static Inboxes durableQueueBasedInboxes(DurableQueues durableQueues,
                                            FencedLockManager fencedLockManager) {
        return new DurableQueueBasedInboxes(durableQueues,
                                            fencedLockManager);
    }

    /**
     * {@link Inboxes} variant that uses {@link DurableQueues} as the underlying implementation.<br>
     * ONLY in cases where the underlying {@link DurableQueues} is associated with a {@link UnitOfWorkFactory} will
     * the {@link Inbox} message consumption be performed within {@link UnitOfWork}, otherwise
     * message consumption isn't performed with a {@link UnitOfWork}
     */
    class DurableQueueBasedInboxes implements Inboxes {
        private final DurableQueues                   durableQueues;
        private final FencedLockManager               fencedLockManager;
        private       ConcurrentMap<InboxName, Inbox> inboxes = new ConcurrentHashMap<>();

        public DurableQueueBasedInboxes(DurableQueues durableQueues, FencedLockManager fencedLockManager) {
            this.durableQueues = requireNonNull(durableQueues, "No durableQueues instance provided");
            this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager instance provided");
        }

        @SuppressWarnings("unchecked")
        @Override
        public Inbox getOrCreateInbox(InboxConfig inboxConfig,
                                      Consumer<Message> messageConsumer) {
            requireNonNull(inboxConfig, "No inboxConfig provided");
            return inboxes.computeIfAbsent(inboxConfig.getInboxName(),
                                           inboxName_ -> new DurableQueueBasedInbox(inboxConfig,
                                                                                    messageConsumer));
        }

        @SuppressWarnings("unchecked")
        @Override
        public Inbox getOrCreateInbox(InboxConfig inboxConfig) {
            requireNonNull(inboxConfig, "No inboxConfig provided");
            return inboxes.computeIfAbsent(inboxConfig.getInboxName(),
                                           inboxName_ -> new DurableQueueBasedInbox(inboxConfig));
        }

        @Override
        public Collection<Inbox> getInboxes() {
            return inboxes.values();
        }

        public class DurableQueueBasedInbox implements Inbox {
            private static final Logger log = LoggerFactory.getLogger(DurableQueueBasedInbox.class);
            private      Consumer<Message>    messageConsumer;
            public final QueueName            inboxQueueName;
            public final InboxConfig          config;
            private      DurableQueueConsumer durableQueueConsumer;

            public DurableQueueBasedInbox(InboxConfig config,
                                          Consumer<Message> messageConsumer) {
                this(config);
                consume(messageConsumer);
            }

            public DurableQueueBasedInbox(InboxConfig config) {
                this.config = requireNonNull(config, "No inbox config provided");
                inboxQueueName = config.inboxName.asQueueName();
            }

            @Override
            public Inbox consume(Consumer<Message> messageConsumer) {
                if (this.messageConsumer != null) {
                    throw new IllegalStateException("Inbox already has a message consumer");
                }
                setMessageConsumer(messageConsumer);
                startConsuming();
                return this;
            }

            @Override
            public Inbox setMessageConsumer(Consumer<Message> messageConsumer) {
                this.messageConsumer = requireNonNull(messageConsumer, "No messageConsumer provided");
                return this;
            }

            @Override
            public Inbox startConsuming() {
                if (this.messageConsumer == null) {
                    throw new IllegalStateException("No message consumer specified. Please call #setMessageConsumer");
                }
                log.info("Starting Consuming from Inbox '{}'", config.inboxName);
                switch (config.messageConsumptionMode) {
                    case SingleGlobalConsumer:
                        var lockName = config.inboxName.asLockName();
                        log.info("Creating FencedLock '{}' for Consumer for Inbox '{}'", lockName, config.inboxName);
                        fencedLockManager.acquireLockAsync(lockName,
                                                           LockCallback.builder()
                                                                       .onLockAcquired(lock -> {
                                                                           log.info("FencedLock '{}' for Inbox '{}' was ACQUIRED - will start Exclusive DurableQueueConsumer", lockName, config.inboxName);
                                                                           durableQueueConsumer = consumeFromDurableQueue(lock);
                                                                           log.info("Exclusive DurableQueueConsumer for Inbox '{}': {}", config.inboxName, durableQueueConsumer);
                                                                       })
                                                                       .onLockReleased(lock -> {
                                                                           if (durableQueueConsumer != null) {
                                                                               log.info("FencedLock '{}' for Inbox '{}' was RELEASED - will stop Exclusive DurableQueueConsumer: {}", lockName, config.inboxName, durableQueueConsumer);
                                                                               durableQueueConsumer.cancel();
                                                                               log.info("Stopped Exclusive DurableQueueConsumer for Inbox '{}': {}", config.inboxName, durableQueueConsumer);
                                                                           } else {
                                                                               log.warn("FencedLock '{}' for Inbox '{}' was RELEASED - didn't find an Exclusive DurableQueueConsumer!", lockName, config.inboxName);
                                                                           }
                                                                       })
                                                                       .build());
                        break;
                    case GlobalCompetingConsumers:
                        log.info("Starting Non-Exclusive DurableQueueConsumer for Inbox '{}'", config.inboxName);
                        durableQueueConsumer = consumeFromDurableQueue(null);
                        log.info("Non-Exclusive DurableQueueConsumer for Inbox '{}': {}", config.inboxName, durableQueueConsumer);
                        break;
                    default:
                        throw new IllegalStateException("Unexpected messageConsumptionMode: " + config.messageConsumptionMode);
                }
                return this;
            }

            @Override
            public boolean hasAMessageConsumer() {
                return messageConsumer != null;
            }

            @Override
            public boolean isConsumingMessages() {
                return durableQueueConsumer != null;
            }

            @Override
            public Inbox stopConsuming() {
                if (messageConsumer != null) {
                    log.info("Stop Consuming from Inbox '{}'", config.inboxName);
                    switch (config.messageConsumptionMode) {
                        case SingleGlobalConsumer:
                            var lockName = config.inboxName.asLockName();
                            log.info("CancelAsyncLockAcquiring FencedLock '{}' for Inbox '{}'", lockName, config.inboxName);
                            fencedLockManager.cancelAsyncLockAcquiring(lockName);
                            break;
                        case GlobalCompetingConsumers:
                            if (durableQueueConsumer != null) {
                                log.info("Stopping Non-Exclusive DurableQueueConsumer for Inbox '{}': {}", config.inboxName, durableQueueConsumer);
                                durableQueueConsumer.cancel();
                                durableQueueConsumer = null;
                            }
                            break;
                        default:
                            throw new IllegalStateException("Unexpected messageConsumptionMode: " + config.messageConsumptionMode);
                    }
                    messageConsumer = null;
                }
                return this;
            }

            @Override
            public InboxName name() {
                return config.inboxName;
            }

            @Override
            public void deleteAllMessages() {
                durableQueues.purgeQueue(inboxQueueName);
            }

            @Override
            public Inbox addMessageReceived(Message message) {
                // An Inbox is usually used to bridge receiving messages from a Messaging system
                // In these cases we rarely have other business logic that's already started a Transaction/UnitOfWork.
                // So to simplify using the Inbox we allow adding a message to start a UnitOfWork if none exists

                if (durableQueues.getTransactionalMode() == TransactionalMode.FullyTransactional) {
                    // Allow addMessageReceived to automatically start a new or join in an existing UnitOfWork
                    durableQueues.getUnitOfWorkFactory().get().usingUnitOfWork(() -> {
                        durableQueues.queueMessage(inboxQueueName,
                                                   message);
                    });
                } else {
                    durableQueues.queueMessage(inboxQueueName,
                                               message);
                }
                return this;
            }

            @Override
            public Inbox addMessageReceived(Message message, Duration deliveryDelay) {
                // An Inbox is usually used to bridge receiving messages from a Messaging system
                // In these cases we rarely have other business logic that's already started a Transaction/UnitOfWork.
                // So to simplify using the Inbox we allow adding a message to start a UnitOfWork if none exists

                if (durableQueues.getTransactionalMode() == TransactionalMode.FullyTransactional) {
                    // Allow addMessageReceived to automatically start a new or join in an existing UnitOfWork
                    durableQueues.getUnitOfWorkFactory().get().usingUnitOfWork(() -> {
                        durableQueues.queueMessage(inboxQueueName,
                                                   message,
                                                   deliveryDelay);
                    });
                } else {
                    durableQueues.queueMessage(inboxQueueName,
                                               message,
                                               deliveryDelay);
                }
                return this;
            }

            @Override
            public Inbox addMessagesReceived(List<Message> messages) {
                if (durableQueues.getTransactionalMode() == TransactionalMode.FullyTransactional) {
                    // Allow addMessagesReceived to automatically start a new or join in an existing UnitOfWork
                    durableQueues.getUnitOfWorkFactory().get().usingUnitOfWork(() -> {
                        durableQueues.queueMessages(inboxQueueName, messages);
                    });
                } else {
                    durableQueues.queueMessages(inboxQueueName, messages);
                }
                return this;
            }

            @Override
            public Inbox addMessagesReceived(List<Message> messages, Duration deliveryDelay) {
                if (durableQueues.getTransactionalMode() == TransactionalMode.FullyTransactional) {
                    // Allow addMessagesReceived to automatically start a new or join in an existing UnitOfWork
                    durableQueues.getUnitOfWorkFactory().get().usingUnitOfWork(() -> {
                        durableQueues.queueMessages(inboxQueueName,
                                                    messages,
                                                    deliveryDelay);
                    });
                } else {
                    durableQueues.queueMessages(inboxQueueName,
                                                messages,
                                                deliveryDelay);
                }
                return this;
            }

            private DurableQueueConsumer consumeFromDurableQueue(FencedLock lock) {
                return durableQueues.consumeFromQueue(inboxQueueName,
                                                      config.redeliveryPolicy,
                                                      config.numberOfParallelMessageConsumers,
                                                      queuedMessage -> {
                                                          if (config.messageConsumptionMode == SingleGlobalConsumer) {
                                                              queuedMessage.getMetaData().put(MessageMetaData.FENCED_LOCK_TOKEN,
                                                                                              lock.getCurrentToken().toString());
                                                          }
                                                          handleMessage(queuedMessage);
                                                      });
            }

            @SuppressWarnings("unchecked")
            private void handleMessage(QueuedMessage queuedMessage) {
                if (durableQueues.getUnitOfWorkFactory().isPresent()) {
                    durableQueues.getUnitOfWorkFactory().get()
                                 .usingUnitOfWork(() -> messageConsumer.accept(queuedMessage.getMessage()));
                } else {
                    messageConsumer.accept(queuedMessage.getMessage());
                }
            }

            @Override
            public long getNumberOfUndeliveredMessages() {
                return durableQueues.getTotalMessagesQueuedFor(inboxQueueName);
            }

            @Override
            public String toString() {
                return "DurableQueueBasedInbox{" +
                        "config=" + config + ", " +
                        "inboxQueueName=" + inboxQueueName +
                        '}';
            }
        }
    }
}
