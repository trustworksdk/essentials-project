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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import io.micrometer.core.instrument.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.DurableQueuesMicrometerInterceptor.MODULE_TAG_NAME;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Maintains the {@value #SUBSCRIPTION_EVENT_ORDER_DIFF_METRIC} gauge: how many events of its {@link AggregateType} a
 * subscriber has yet to read - the highest {@link GlobalEventOrder} persisted, minus the subscriber's resume point (the
 * next order it reads), plus one. A subscriber that has handled everything reads {@code 0}; one event behind reads
 * {@code 1}. Holes in the global order (rolled-back appends) are counted as events, so the value is an upper bound.
 * <p>
 * A resume point more than one past the highest persisted order is <i>ahead of the head</i> - the sign of a reset or
 * restored database, or a resume point moved by hand. Such a subscriber silently skips every event appended at an
 * order below its resume point. The gauge reads {@code 0} then (it stays non-negative, so a {@code lag > N} alert keeps
 * its meaning), and a WARN names the subscriber, its resume point and the head - once each time it gets ahead.
 * <p>
 * Without a {@link MeterRegistry} the monitor does nothing. It never throws: an exception escaping a monitor would
 * cancel the {@link EventStoreSubscriptionMonitorManager}'s schedule, so a failure is logged at WARN instead.
 * <p>
 * Tags: {@code SubscriberId}, {@code AggregateType} and the optional {@code MODULE_TAG_NAME}
 */
public class SubscriberGlobalOrderMicrometerMonitor implements EventStoreSubscriptionMonitor {
    private static final Logger log = LoggerFactory.getLogger(SubscriberGlobalOrderMicrometerMonitor.class);
    /**
     * Gauge: the number of events the subscriber has yet to read, {@code 0} when caught up or ahead of the head
     */
    public static final  String SUBSCRIPTION_EVENT_ORDER_DIFF_METRIC = "DurableSubscriptions_EventOrder_Diff";
    private static final String SUBSCRIBER_ID_TAG                    = "SubscriberId";
    private static final String AGGREGATE_TYPE_TAG                   = "AggregateType";

    private final EventStoreSubscriptionManager                                               eventStoreSubscriptionManager;
    /**
     * Nullable: without it nothing is read and no gauge is registered
     */
    private final MeterRegistry                                                               meterRegistry;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork>                 unitOfWorkFactory;
    private final ConcurrentHashMap<Pair<SubscriberId, AggregateType>, Pair<Gauge, AtomicLong>> subscriberGauges   = new ConcurrentHashMap<>();
    /**
     * The subscriptions whose resume point was ahead of the head when last monitored - so each episode is warned about once
     */
    private final Set<Pair<SubscriberId, AggregateType>>                                       aheadOfTheHead     = ConcurrentHashMap.newKeySet();
    private final List<Tag>                                                                   commonTags         = new ArrayList<>();

    /**
     * @param eventStoreSubscriptionManager the {@link EventStoreSubscriptionManager} the resume points are read from
     * @param meterRegistry                 where the gauge is registered. May be {@code null}, in which case the monitor does nothing
     * @param unitOfWorkFactory             the unit of work factory the highest persisted global order is read in
     * @param moduleTag                     optional {@code MODULE_TAG_NAME} tag value. May be {@code null}, in which case the tag is omitted
     */
    public SubscriberGlobalOrderMicrometerMonitor(EventStoreSubscriptionManager eventStoreSubscriptionManager,
                                                  MeterRegistry meterRegistry,
                                                  EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                  String moduleTag) {
        this.eventStoreSubscriptionManager = requireNonNull(eventStoreSubscriptionManager, "EventStoreSubscriptionManager must be provided");
        this.meterRegistry = meterRegistry;
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "Unit of work factory is required");
        Optional.ofNullable(moduleTag).map(t -> Tag.of(MODULE_TAG_NAME, t)).ifPresent(commonTags::add);
    }

    @Override
    public void monitor(SubscriberId subscriberId, AggregateType aggregateType) {
        if (meterRegistry == null) {
            return;
        }
        try {
            unitOfWorkFactory.usingUnitOfWork(() -> doExecuteMonitoring(subscriberId, aggregateType));
        } catch (RuntimeException e) {
            log.warn(msg("Failed to determine the lag of subscriber '{}' on aggregateType '{}'", subscriberId, aggregateType), e);
        }
    }

    private void doExecuteMonitoring(SubscriberId subscriberId, AggregateType aggregateType) {
        var key = Pair.of(subscriberId, aggregateType);
        eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, aggregateType)
                                     .ifPresent(resumeFromAndIncluding -> {
                                         var highestPersisted = findHighestGlobalEventOrderPersisted(aggregateType);
                                         // The resume point is the next order to read, so everything from it up to and including the head is unread
                                         var lag = highestPersisted - resumeFromAndIncluding.longValue() + 1;
                                         if (lag < 0) {
                                             if (aheadOfTheHead.add(key)) {
                                                 log.warn("Subscriber '{}' on aggregateType '{}' resumes from global order {}, but the highest global order persisted is {}: " +
                                                                  "its resume point is ahead of the event stream - typical of a reset or restored database, or a " +
                                                                  "resume point moved by hand. Every event appended below global order {} is skipped by it. " +
                                                                  "Check the resume point and reset the subscription if needed",
                                                          subscriberId, aggregateType, resumeFromAndIncluding, highestPersisted, resumeFromAndIncluding);
                                             }
                                             lag = 0;
                                         } else if (aheadOfTheHead.remove(key)) {
                                             log.info("Subscriber '{}' on aggregateType '{}' is no longer ahead of the event stream: it resumes from global order {}, " +
                                                              "the highest global order persisted is {}",
                                                      subscriberId, aggregateType, resumeFromAndIncluding, highestPersisted);
                                         }
                                         if (lag > 0) {
                                             log.debug("Subscriber lag found for subscriber '{}' on aggregateType '{}'. Lag is {}", subscriberId, aggregateType, lag);
                                         } else {
                                             log.debug("No Subscriber lag found for subscriber '{}' on aggregateType '{}'.", subscriberId, aggregateType);
                                         }
                                         subscriberGauges.computeIfAbsent(key, this::initializeEventOrderDiffCountGauge)
                                                 ._2.set(lag);
                                     });
    }

    private Pair<Gauge, AtomicLong> initializeEventOrderDiffCountGauge(Pair<SubscriberId, AggregateType> key) {
        var eventOrderDifferenceCount = new AtomicLong();
        return Pair.of(buildGauge(key, eventOrderDifferenceCount), eventOrderDifferenceCount);
    }

    private Gauge buildGauge(Pair<SubscriberId, AggregateType> pair, AtomicLong eventOrderDifferenceCount) {
        var subscriberId = pair._1;
        var aggregateType = pair._2;

        var tags = new ArrayList<>(commonTags);
        tags.add(Tag.of(SUBSCRIBER_ID_TAG, subscriberId.toString()));
        tags.add(Tag.of(AGGREGATE_TYPE_TAG, aggregateType.toString()));
        return Gauge.builder(SUBSCRIPTION_EVENT_ORDER_DIFF_METRIC, eventOrderDifferenceCount::get)
            .tags(tags)
            .register(meterRegistry);
    }

    /**
     * @return the highest global order persisted, or the order before {@link GlobalEventOrder#FIRST_GLOBAL_EVENT_ORDER}
     * when nothing is persisted - so a subscriber resuming from the first order reads no lag
     */
    private long findHighestGlobalEventOrderPersisted(AggregateType aggregateType) {
        return eventStoreSubscriptionManager.getEventStore()
                                            .findHighestGlobalEventOrderPersisted(aggregateType)
                                            .map(GlobalEventOrder::longValue)
                                            .orElse(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue() - 1);
    }
}
