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
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.collections.Lists.nullSafeList;

public class EventStoreSubscriptionMonitorManager implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(EventStoreSubscriptionMonitorManager.class);
    private boolean started;
    private final boolean enabled;
    private final Duration interval;
    private final List<EventStoreSubscriptionMonitor> monitors = new ArrayList<>();
    private final EventStoreSubscriptionManager eventStoreSubscriptionManager;
    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> scheduledFuture;

    public EventStoreSubscriptionMonitorManager(boolean enabled,
                                                Duration interval,
                                                EventStoreSubscriptionManager eventStoreSubscriptionManager,
                                                List<EventStoreSubscriptionMonitor> monitors) {
        this.enabled = enabled;
        this.interval = requireNonNull(interval, "Interval must be provided");
        this.monitors.addAll(nullSafeList(monitors));
        this.eventStoreSubscriptionManager = requireNonNull(eventStoreSubscriptionManager, "SubscriptionManager must be provided");
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("[{}] is disabled", this.getClass().getSimpleName());
            return;
        }
        if (this.monitors.isEmpty()) {
            log.info("[{}] No monitors configured", this.getClass().getSimpleName());
            return;
        }
        if (!started) {
            log.info("Starting [{}]", this.getClass().getSimpleName());
            executorService = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder.builder()
                                                                                                       .nameFormat("EventStoreSubscriptionMonitoring")
                                                                                                       .daemon(true)
                                                                                                       .build());
            scheduledFuture = executorService.scheduleAtFixedRate(this::executeMonitoring,
                    interval.toMillis(),
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS);
            started = true;
        } else {
            log.debug("[{}] was already started", this.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (started) {
            log.info("Stopping [{}]", this.getClass().getSimpleName());
            scheduledFuture.cancel(true);
            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }
            started = false;
        } else {
            log.debug("[{}] was already stopped", this.getClass().getSimpleName());
        }
    }

    private void executeMonitoring() {
        Set<Pair<SubscriberId, AggregateType>> subscriptions = eventStoreSubscriptionManager.getActiveSubscriptions();
        log.debug("[{}] executing monitoring for {} subscriptions and {} monitors", this.getClass().getSimpleName(), subscriptions.size(), monitors.size());
        subscriptions.forEach(subscription -> executeMonitoring(subscription._1, subscription._2));
    }

    private void executeMonitoring(SubscriberId subscriberId, AggregateType aggregateType) {
        monitors.forEach(monitor -> monitor.monitor(subscriberId, aggregateType));
    }

    @Override
    public boolean isStarted() {
        return started;
    }
}
