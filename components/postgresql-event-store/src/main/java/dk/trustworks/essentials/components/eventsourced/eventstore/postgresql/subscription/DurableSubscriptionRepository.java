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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.tuple.Pair;

import java.util.*;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Repository responsible for persisting {@link SubscriptionResumePoint}'s maintained by the {@link EventStoreSubscriptionManager}
 *
 * @see PostgresqlDurableSubscriptionRepository
 */
public interface DurableSubscriptionRepository {

    /**
     * Create an initial Resume Point
     *
     * @param subscriberIdAggregateTypePair                           the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder the {@link GlobalEventOrder} resume point
     * @return the resume point
     * @throws IllegalStateException in case the resume point already exists. Consider using {@link #getOrCreateResumePoint(SubscriberId, AggregateType, GlobalEventOrder)}
     */
    default SubscriptionResumePoint createResumePoint(Pair<SubscriberId, AggregateType> subscriberIdAggregateTypePair,
                                                      GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
        requireNonNull(subscriberIdAggregateTypePair, "No subscriberIdAggregateTypePair value provided");
        return createResumePoint(subscriberIdAggregateTypePair._1,
                                 subscriberIdAggregateTypePair._2,
                                 onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
    }

    /**
     * Create an initial Resume Point for the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point
     *
     * @param subscriberId                                            the subscriber id
     * @param forAggregateType                                        the aggregate type
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder the {@link GlobalEventOrder} resume point
     * @return the resume point
     * @throws IllegalStateException in case the resume point already exists - the default {@link #getOrCreateResumePoint(SubscriberId, AggregateType, Function)}
     *                               relies on this to recognise a resume point another caller created. Consider using {@link #getOrCreateResumePoint(SubscriberId, AggregateType, GlobalEventOrder)}
     */
    SubscriptionResumePoint createResumePoint(SubscriberId subscriberId,
                                              AggregateType forAggregateType,
                                              GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);

    /**
     * Get Resume Point
     *
     * @param subscriberIdAggregateTypePair the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point
     * @return an {@link Optional} with the resume point, or an {@link Optional#empty()} if the resume point doesn't exist
     */
    default Optional<SubscriptionResumePoint> getResumePoint(Pair<SubscriberId, AggregateType> subscriberIdAggregateTypePair) {
        requireNonNull(subscriberIdAggregateTypePair, "No subscriberIdAggregateTypePair value provided");
        return getResumePoint(subscriberIdAggregateTypePair._1,
                              subscriberIdAggregateTypePair._2);
    }

    /**
     * Get Resume Point for the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point
     *
     * @param subscriberId     the subscriber id
     * @param forAggregateType the aggregate type
     * @return an {@link Optional} with the resume point, or an {@link Optional#empty()} if the resume point doesn't exist
     */
    Optional<SubscriptionResumePoint> getResumePoint(SubscriberId subscriberId,
                                                     AggregateType forAggregateType);

    /**
     * Delete a resume point
     *
     * @param resumePoint the resume point to delete
     */
    default void deleteResumePoint(SubscriptionResumePoint resumePoint) {
        requireNonNull(resumePoint, "No resumePoint provided");
        deleteResumePoint(resumePoint.getSubscriberId(),
                          resumePoint.getAggregateType());
    }

    /**
     * Delete the Resume Point for the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point
     *
     * @param subscriberId  the subscriber id
     * @param aggregateType the aggregate type
     */
    void deleteResumePoint(SubscriberId subscriberId, AggregateType aggregateType);


    /**
     * Save the resume point if it's {@link SubscriptionResumePoint#isChanged()}
     *
     * @param resumePoint the resume point to save
     */
    default void saveResumePoint(SubscriptionResumePoint resumePoint) {
        requireNonNull(resumePoint, "No resumePoint provided");
        saveResumePoints(List.of(resumePoint));
    }

    /**
     * Batch save all the resume points. Only {@link SubscriptionResumePoint#isChanged()} Resume Points will saved<br>
     * Implementations must write each resume point's {@link SubscriptionResumePoint#snapshot()} - value and
     * {@link SubscriptionResumePoint#getRepositionEpoch() reposition epoch} together - and refuse the write when the stored
     * epoch is newer, so a save that captured a resume point before a reset cannot overwrite the reset. A written snapshot
     * is recorded with {@link SubscriptionResumePoint#markAsPersisted(SubscriptionResumePoint.Snapshot, java.time.OffsetDateTime)},
     * a refused one with {@link SubscriptionResumePoint#markAsSuperseded(SubscriptionResumePoint.Snapshot)}
     *
     * @param resumePoints the resume points to save
     */
    void saveResumePoints(Collection<SubscriptionResumePoint> resumePoints);

    /**
     * Get or Create a Resume point for the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point<br>
     * Same contract as {@link #getOrCreateResumePoint(SubscriberId, AggregateType, Function)}, which this delegates to
     *
     * @param subscriberId                                            the subscriber id
     * @param forAggregateType                                        the aggregate type
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder the {@link GlobalEventOrder} resume point that will be used if the resume didn't exist
     * @return the resume point
     */
    default SubscriptionResumePoint getOrCreateResumePoint(SubscriberId subscriberId,
                                                           AggregateType forAggregateType,
                                                           GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
        return getOrCreateResumePoint(subscriberId, forAggregateType, p -> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
    }

    /**
     * Get or Create a Resume point for the combination of {@link SubscriberId} and {@link AggregateType} which uniquely define a resume point<br>
     * <b>Concurrency contract:</b> implementations must be safe when two callers get-or-create the same resume point at the same
     * time - two subscription starts for the same subscriber, on two threads or two instances. Both find none and both create
     * one; each caller must get the stored resume point back, and neither may fail because the other created it first.<br>
     * The default: {@link #getResumePoint(SubscriberId, AggregateType)}; if the resume point doesn't exist then
     * {@link #createResumePoint(SubscriberId, AggregateType, GlobalEventOrder)}; if that throws {@link IllegalStateException}
     * (the resume point already exists, as its contract says) then {@link #getResumePoint(SubscriberId, AggregateType)} again,
     * returning the resume point the other caller stored, or rethrowing the {@link IllegalStateException} when there still is none.
     * The default is only safe for a store whose {@code createResumePoint} throws {@link IllegalStateException} on a duplicate and
     * leaves the caller able to read afterwards; a store that fails a duplicate any other way (another exception, or an aborted
     * transaction the re-read then runs in) must override this method.<br>
     * Reference shape: {@link PostgresqlDurableSubscriptionRepository#getOrCreateResumePoint(SubscriberId, AggregateType, Function)} -
     * get, insert-if-absent ({@code ON CONFLICT DO NOTHING}), and when the insert stored nothing read again
     *
     * @param subscriberId                                            the subscriber id
     * @param forAggregateType                                        the aggregate type
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder the {@link GlobalEventOrder} resume point that will be used if the resume didn't exist
     * @return the resume point
     */
    default SubscriptionResumePoint getOrCreateResumePoint(SubscriberId subscriberId,
                                                           AggregateType forAggregateType,
                                                           Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
        return getResumePoint(subscriberId,
                forAggregateType)
                .orElseGet(() -> {
                    try {
                        return createResumePoint(subscriberId,
                                forAggregateType,
                                onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder.apply(forAggregateType));
                    } catch (IllegalStateException alreadyExists) {
                        // Another caller created it between our get and our create - return the one it stored
                        return getResumePoint(subscriberId, forAggregateType).orElseThrow(() -> alreadyExists);
                    }
                });
    }

    /**
     * Finds and retrieves all existing subscription resume points.
     *
     * @return a list of all {@link SubscriptionResumePoint} instances stored in the repository
     */
    List<SubscriptionResumePoint> findAllResumePoints();

    /**
     * Retrieves a paginated list of subscription resume points starting from the specified index.
     *
     * @param startIndex the starting index for the pagination
     * @param pageSize   the maximum number of subscription resume points to retrieve
     * @return a list of {@link SubscriptionResumePoint} instances within the specified range
     */
    List<SubscriptionResumePoint> findAllResumePoints(long startIndex, long pageSize);

}