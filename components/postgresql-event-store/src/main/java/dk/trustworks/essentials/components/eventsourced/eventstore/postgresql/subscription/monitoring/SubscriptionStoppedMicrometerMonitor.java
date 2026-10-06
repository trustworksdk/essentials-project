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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import io.micrometer.core.instrument.*;
import org.slf4j.*;

import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver.MODULE_TAG_NAME;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Maintains the level-triggered {@value #SUBSCRIPTION_STOPPED_METRIC} gauge: {@code 1} while a subscription is stopped
 * by its {@link SubscriptionErrorPolicy} ({@link EventStoreSubscription#isStoppedByErrorPolicy()}) or has been resumed
 * but not yet got past the event it stopped at ({@link EventStoreSubscription#isRecoveringFromErrorPolicyStop()}),
 * {@code 0} otherwise. A subscription resumed automatically at a poison event retries it for a moment before it stops
 * again, so the gauge stays at {@code 1} through every resume until the event is handled, handed off or skipped - an
 * alert with a {@code for:} duration is not reset by the resumes. The value is
 * {@link EventStoreSubscription#isStoppedOrRecoveringFromErrorPolicyStop()}, which answers both from one read of the
 * subscription's state: combining the two calls could read {@code 0} at the moment a resumed subscription stops again.
 * <p>
 * This is the signal to alert on for a halted projection, e.g. {@code max by (subscriber_id, aggregate_type) (essentials_eventstore_subscription_stopped) == 1}.
 * The {@value MeasurementEventStoreSubscriptionObserver#SUBSCRIPTION_STOPPED_BY_ERROR_POLICY_METRIC} counter only
 * records that a stop <i>happened</i>: an {@code increase(...) > 0} alert on it resolves once the window has passed
 * while the subscription is still stopped, and a {@code > 0} alert keeps firing after the subscription has been started
 * again (application restart, fenced-lock hand-over, {@link EventStoreSubscription#resetFrom}, or unsubscribe + subscribe).
 * <p>
 * The gauge is registered the first time the {@link EventStoreSubscriptionMonitorManager} monitors a subscription, i.e.
 * within one monitoring interval of the subscription becoming active. Its value is read from the
 * {@link EventStoreSubscriptionManager} each time the gauge is sampled, never cached, so it follows a restart at once,
 * and a subscription that is no longer registered with the manager (unsubscribed) reports {@code 0}. Like
 * {@link SubscriberGlobalOrderMicrometerMonitor}, the gauge itself is kept once registered.
 * <p>
 * The gauge reports per JVM: an exclusive subscription reports {@code 1} only on the instance that holds its fenced
 * lock, so aggregate across instances with {@code max}, not {@code sum}.
 * <p>
 * Tags: {@code subscriber_id}, {@code aggregate_type} (the same tags as the
 * {@value MeasurementEventStoreSubscriptionObserver#SUBSCRIPTION_STOPPED_BY_ERROR_POLICY_METRIC} counter) and the
 * optional {@value MeasurementEventStoreSubscriptionObserver#MODULE_TAG_NAME}
 */
public class SubscriptionStoppedMicrometerMonitor implements EventStoreSubscriptionMonitor {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionStoppedMicrometerMonitor.class);
    /**
     * Gauge: {@code 1} while the subscription is stopped by its {@link SubscriptionErrorPolicy}, or resumed but not yet past
     * the event it stopped at, {@code 0} otherwise
     */
    public static final  String SUBSCRIPTION_STOPPED_METRIC = "essentials.eventstore.subscription.stopped";
    private static final String SUBSCRIBER_ID_TAG           = "subscriber_id";
    private static final String AGGREGATE_TYPE_TAG          = "aggregate_type";

    private final EventStoreSubscriptionManager                                eventStoreSubscriptionManager;
    /**
     * Nullable: without it no gauge is registered
     */
    private final MeterRegistry                                                meterRegistry;
    private final String                                                       moduleTag;
    private final ConcurrentHashMap<Pair<SubscriberId, AggregateType>, Gauge> subscriberGauges = new ConcurrentHashMap<>();

    /**
     * @param eventStoreSubscriptionManager the {@link EventStoreSubscriptionManager} the subscriptions' current state is read from
     * @param meterRegistry                 where the {@value #SUBSCRIPTION_STOPPED_METRIC} gauge is registered. May be {@code null}, in which case no gauge is registered
     * @param moduleTag                     Optional {@value MeasurementEventStoreSubscriptionObserver#MODULE_TAG_NAME} Tag value. May be {@code null}, in which case the tag is omitted
     */
    public SubscriptionStoppedMicrometerMonitor(EventStoreSubscriptionManager eventStoreSubscriptionManager,
                                                MeterRegistry meterRegistry,
                                                String moduleTag) {
        this.eventStoreSubscriptionManager = requireNonNull(eventStoreSubscriptionManager, "EventStoreSubscriptionManager must be provided");
        this.meterRegistry = meterRegistry;
        this.moduleTag = moduleTag;
    }

    @Override
    public void monitor(SubscriberId subscriberId, AggregateType aggregateType) {
        if (meterRegistry == null) {
            return;
        }
        // Never throws: an exception escaping a monitor cancels the EventStoreSubscriptionMonitorManager's schedule
        try {
            subscriberGauges.computeIfAbsent(Pair.of(subscriberId, aggregateType), this::registerGauge);
        } catch (RuntimeException e) {
            log.warn(msg("Failed to register the '{}' gauge for subscriber '{}' on aggregateType '{}'",
                         SUBSCRIPTION_STOPPED_METRIC, subscriberId, aggregateType), e);
        }
    }

    private Gauge registerGauge(Pair<SubscriberId, AggregateType> key) {
        var subscriberId  = key._1;
        var aggregateType = key._2;
        var builder = Gauge.builder(SUBSCRIPTION_STOPPED_METRIC, () -> reportsStopped(subscriberId, aggregateType) ? 1 : 0)
                           .description("1 while the subscription is stopped by its SubscriptionErrorPolicy, or resumed but not yet past the event it stopped at, 0 otherwise")
                           .tag(SUBSCRIBER_ID_TAG, subscriberId.toString())
                           .tag(AGGREGATE_TYPE_TAG, aggregateType.toString());
        if (moduleTag != null) {
            builder.tag(MODULE_TAG_NAME, moduleTag);
        }
        return builder.register(meterRegistry);
    }

    private boolean reportsStopped(SubscriberId subscriberId, AggregateType aggregateType) {
        return eventStoreSubscriptionManager.getSubscription(subscriberId, aggregateType)
                                            .map(EventStoreSubscription::isStoppedOrRecoveringFromErrorPolicyStop)
                                            .orElse(false);
    }
}
