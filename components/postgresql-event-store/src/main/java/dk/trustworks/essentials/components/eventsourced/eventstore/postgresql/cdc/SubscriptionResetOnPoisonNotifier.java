/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.slf4j.*;

import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class SubscriptionResetOnPoisonNotifier implements CdcPoisonNotifier {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionResetOnPoisonNotifier.class);

    private final EventStoreSubscriptionManager subscriptionManager;
    private final DurableSubscriptionRepository durableSubscriptionRepository;

    public SubscriptionResetOnPoisonNotifier(EventStoreSubscriptionManager subscriptionManager,
                                             DurableSubscriptionRepository durableSubscriptionRepository) {
        this.subscriptionManager = requireNonNull(subscriptionManager, "No subscriptionManager provided");
        this.durableSubscriptionRepository = requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
    }

    @Override
    public void onPoison(AggregateType aggregateType,
                         List<GlobalEventOrder> gaps,
                         String reason) {
        requireNonNull(aggregateType, "No aggregateType provided");
        if (gaps == null || gaps.isEmpty()) return;

        GlobalEventOrder minGap = gaps.stream()
                                      .min(Comparator.comparingLong(GlobalEventOrder::longValue))
                                      .orElseThrow();

        subscriptionManager.getActiveSubscriptions().stream()
                           .filter(p -> p._2.equals(aggregateType))
                           .forEach(p -> {
                               SubscriberId subscriberId = p._1;

                               subscriptionManager.getSubscription(subscriberId, aggregateType)
                                                  .ifPresent(sub -> resetOne(subscriberId, aggregateType, sub, minGap, reason));
                           });
    }

    private void resetOne(SubscriberId subscriberId,
                          AggregateType aggregateType,
                          EventStoreSubscription subscription,
                          GlobalEventOrder requestedResetFrom,
                          String reason) {
        if (subscription.isInTransaction()) {
            log.debug("Skipping reset-after-poison for {}-{} as the subscription is in a transaction '{}'",
                      subscriberId, aggregateType, subscription.getClass().getSimpleName());
            return;
        }

        // Best effort: prefer in-memory resume (more current), fall back to durable
        var currentResumeOpt = subscription.currentResumePoint()
                                           .map(SubscriptionResumePoint::getResumeFromAndIncluding)
                                           .or(() -> durableSubscriptionRepository
                                                   .getResumePoint(subscriberId, aggregateType)
                                                   .map(SubscriptionResumePoint::getResumeFromAndIncluding));

        // Clamp: never move resume forward due to poison reset
        var effectiveResetFrom = currentResumeOpt.filter(cur -> cur.longValue() <= requestedResetFrom.longValue()).orElse(requestedResetFrom);

        if (currentResumeOpt.isPresent()
                && currentResumeOpt.get().longValue() == effectiveResetFrom.longValue()) {
            if (requestedResetFrom.longValue() > effectiveResetFrom.longValue()) {
                log.debug(
                        "[{}-{}] reset-after-poison skipped (subscription at {} has not yet reached poison gap {}). reason='{}'",
                        subscriberId,
                        aggregateType,
                        effectiveResetFrom,
                        requestedResetFrom,
                        reason
                         );
            } else {
                log.debug(
                        "[{}-{}] reset-after-poison no-op (already at {}). reason='{}'",
                        subscriberId,
                        aggregateType,
                        effectiveResetFrom,
                        reason
                         );
            }
            return;
        }

        log.warn("[{}-{}] reset-after-poison from {} (requested {}, current {}) reason='{}'",
                 subscriberId, aggregateType, effectiveResetFrom, requestedResetFrom, currentResumeOpt.orElse(null), reason);

        subscription.resetFrom(effectiveResetFrom, resetPoint -> {
            // Force durable persistence NOW (don’t wait for the async batch save)
            var resumePoint = new SubscriptionResumePoint(subscriberId, aggregateType, resetPoint, OffsetDateTime.now(Clock.systemUTC()));
            resumePoint.setResumeFromAndIncluding(resetPoint);
            durableSubscriptionRepository.saveResumePoint(resumePoint);
        });
    }

}