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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * EF-2 measurement M4 (design note {@code docs/key-partitioned-event-processing.md} §3.1 (a1), §5): the proposed
 * topology, built from parts that exist today. One reader in the cluster, behind a {@link FencedLock}, holds a
 * <em>batched</em> subscription to the {@code Accounts} stream and forwards each batch into an inbox with one
 * {@code addMessagesReceived} call - one unit of work and one multi-row enqueue per batch, instead of one per event.
 * Messages carry the event itself (design question Q2's "payload" variant), keyed by aggregate id with its
 * {@code EventOrder} as the order, so the shard-owned engine keeps each aggregate's events in order while every
 * instance consumes ({@code GlobalCompetingConsumers}). The handler only counts, as in M1.
 * <p>
 * Per-key order holds: one reader enqueues in subscription order, in sequential transactions, and an aggregate's
 * events cannot commit out of {@code EventOrder}. The subscriber id carries {@code run-token}, so each measurement
 * reads the existing backlog from the start. Off unless {@code trading-demo.ef2.batched-forwarder=true}; batch size
 * from {@code trading-demo.ef2.batch-size}.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.ef2", name = "batched-forwarder", havingValue = "true")
public class BatchedAccountsForwarder implements DisposableBean {
    private static final Logger    log             = LoggerFactory.getLogger(BatchedAccountsForwarder.class);
    private static final InboxName INBOX           = InboxName.of("Ef2BatchedForward");
    private static final LockName  LOCK            = LockName.of("Ef2BatchedForwarder");
    static final         String    FORWARDED       = "Ef2BatchedForwarded";
    static final         String    HANDLED         = "Ef2BatchedForwardHandled";

    private final EventStoreSubscriptionManager subscriptionManager;
    private final Inboxes                       inboxes;
    private final FencedLockManager             fencedLockManager;
    private final NoOpProcessorStats            stats;
    private final Settings                      settings;

    private volatile Inbox                  inbox;
    private volatile EventStoreSubscription subscription;

    /**
     * @param batchSize  events per batch, the knob M4 sweeps (10 / 100 / 500)
     * @param consumers  inbox consumers per instance
     * @param runToken   part of the subscriber id; a new token reads the stream from the beginning
     */
    public record Settings(int batchSize, int consumers, String runToken) {
    }

    public BatchedAccountsForwarder(EventStoreSubscriptionManager subscriptionManager,
                                    Inboxes inboxes,
                                    FencedLockManager fencedLockManager,
                                    NoOpProcessorStats stats,
                                    @Value("${trading-demo.ef2.batch-size:100}") int batchSize,
                                    @Value("${trading-demo.ef2.consumers:8}") int consumers,
                                    @Value("${trading-demo.ef2.run-token:m4}") String runToken) {
        this.subscriptionManager = requireNonNull(subscriptionManager, "No subscriptionManager provided");
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.stats = requireNonNull(stats, "No stats provided");
        this.settings = new Settings(batchSize, consumers, runToken);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // Every instance consumes; only the lock holder reads and forwards
        inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                    .inboxName(INBOX)
                                                    .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                                                    .numberOfParallelMessageConsumers(settings.consumers())
                                                    .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 10))
                                                    .build(),
                                         message -> stats.handled(HANDLED));
        fencedLockManager.acquireLockAsync(LOCK, new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
                var subscriberId = SubscriberId.of("Ef2BatchedForwarder-" + settings.runToken() + "-" + settings.batchSize());
                log.info("EF-2 M4: reading Accounts as '{}' in batches of {}", subscriberId, settings.batchSize());
                subscription = subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                                 AccountsAggregateTypes.ACCOUNTS,
                                                                                                 GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                 Optional.empty(),
                                                                                                 settings.batchSize(),
                                                                                                 Duration.ofMillis(100),
                                                                                                 BatchedAccountsForwarder.this::forward);
            }

            @Override
            public void lockReleased(FencedLock lock) {
                stopSubscription();
            }
        });
    }

    /**
     * Runs inside the subscription's unit of work: the enqueue commits with it, and the resume point advances only
     * after it has.
     */
    private int forward(List<PersistedEvent> events) {
        var messages = new ArrayList<Message>(events.size());
        for (var event : events) {
            messages.add(OrderedMessage.of(event.event().deserialize(),
                                           event.aggregateId().toString(),
                                           event.eventOrder().longValue()));
        }
        inbox.addMessagesReceived(messages);
        for (var i = 0; i < events.size(); i++) {
            stats.handled(FORWARDED);
        }
        return settings.batchSize();
    }

    private void stopSubscription() {
        var current = subscription;
        subscription = null;
        if (current != null) {
            current.unsubscribe();
        }
    }

    @Override
    public void destroy() {
        fencedLockManager.cancelAsyncLockAcquiring(LOCK);
        stopSubscription();
    }
}
