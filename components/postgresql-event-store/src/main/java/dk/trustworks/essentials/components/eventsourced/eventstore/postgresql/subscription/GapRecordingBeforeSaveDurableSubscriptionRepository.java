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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link DurableSubscriptionRepository} the {@link DefaultEventStoreSubscriptionManager} hands its subscriptions when
 * its event store is a {@link DeferredGapRecording}: every subscriber whose resume point is read or created through it has
 * the gaps its subscriptions open kept in memory ({@link DeferredGapRecording#deferGapRecordingUntilResumePointSave}), and
 * every save of a resume point records them first ({@link DeferredGapRecording#beforeResumePointSave}) - the one place
 * every resume point a managed subscription persists passes through: the periodic saves, the saves on stop and on lock
 * release, and those of a reset.
 * <p>
 * A resume point whose gaps could not be recorded is not saved, and left changed: a saved resume point past a gap nobody
 * recorded would lose the gap's event on a restart. The other resume points of the same save are saved, and then the save
 * throws a {@link GapsNotRecordedException}: the periodic save logs it and tries again next time, a stop or lock release
 * logs a WARN and goes on without settling the resume point, a shutdown counts it as a failed cleanup step and marks the
 * database unreachable when the failure was an IO failure, and a reset fails with it.
 */
final class GapRecordingBeforeSaveDurableSubscriptionRepository implements DurableSubscriptionRepository {
    private final DurableSubscriptionRepository delegate;
    private final DeferredGapRecording          deferredGapRecording;

    /**
     * @return {@code repository}, saving through {@code eventStore} first when it is a {@link DeferredGapRecording}
     */
    static DurableSubscriptionRepository wrapIfDeferringGapRecording(DurableSubscriptionRepository repository, EventStore eventStore) {
        requireNonNull(repository, "No repository provided");
        requireNonNull(eventStore, "No eventStore provided");
        if (eventStore instanceof DeferredGapRecording deferredGapRecording && !(repository instanceof GapRecordingBeforeSaveDurableSubscriptionRepository)) {
            return new GapRecordingBeforeSaveDurableSubscriptionRepository(repository, deferredGapRecording);
        }
        return repository;
    }

    GapRecordingBeforeSaveDurableSubscriptionRepository(DurableSubscriptionRepository delegate, DeferredGapRecording deferredGapRecording) {
        this.delegate = requireNonNull(delegate, "No delegate provided");
        this.deferredGapRecording = requireNonNull(deferredGapRecording, "No deferredGapRecording provided");
    }

    DurableSubscriptionRepository delegate() {
        return delegate;
    }

    @Override
    public SubscriptionResumePoint createResumePoint(SubscriberId subscriberId, AggregateType forAggregateType, GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
        var resumePoint = delegate.createResumePoint(subscriberId, forAggregateType, onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
        deferredGapRecording.deferGapRecordingUntilResumePointSave(subscriberId, forAggregateType);
        return resumePoint;
    }

    @Override
    public Optional<SubscriptionResumePoint> getResumePoint(SubscriberId subscriberId, AggregateType forAggregateType) {
        var resumePoint = delegate.getResumePoint(subscriberId, forAggregateType);
        resumePoint.ifPresent(found -> deferredGapRecording.deferGapRecordingUntilResumePointSave(subscriberId, forAggregateType));
        return resumePoint;
    }

    @Override
    public SubscriptionResumePoint getOrCreateResumePoint(SubscriberId subscriberId,
                                                          AggregateType forAggregateType,
                                                          Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
        var resumePoint = delegate.getOrCreateResumePoint(subscriberId, forAggregateType, onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
        deferredGapRecording.deferGapRecordingUntilResumePointSave(subscriberId, forAggregateType);
        return resumePoint;
    }

    @Override
    public SubscriptionResumePoint getOrCreateResumePoint(SubscriberId subscriberId, AggregateType forAggregateType, GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
        var resumePoint = delegate.getOrCreateResumePoint(subscriberId, forAggregateType, onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
        deferredGapRecording.deferGapRecordingUntilResumePointSave(subscriberId, forAggregateType);
        return resumePoint;
    }

    @Override
    public void deleteResumePoint(SubscriberId subscriberId, AggregateType aggregateType) {
        delegate.deleteResumePoint(subscriberId, aggregateType);
    }

    /**
     * Records the gaps kept in memory for each changed resume point's subscriber before the delegate reads and writes it -
     * and, until it has, records every gap opened at once. Only those resume points are handed to the delegate: one found
     * unchanged here is left out, as it would not be written anyway, so that one its subscription advances after this
     * check is never written without its gaps recorded - a later save takes it.
     * <p>
     * Once waiting for one resume point's gaps to be recorded has run out or been interrupted (a {@link TimeoutException}
     * or {@link InterruptedException} in the failure's cause chain), the gaps of the changed resume points after it are
     * not recorded in this call either, and those are not saved: with the caller's unit of work still current, their
     * recordings may be blocked by the same lock, and each would wait in turn. So one call waits for that at most once.
     *
     * @throws GapsNotRecordedException after the delegate saved the other resume points, when the gaps of one or more
     *                                  resume points could not be recorded, or were not tried after a wait that ran out
     *                                  - those were not saved and stay changed
     */
    @Override
    public void saveResumePoints(Collection<SubscriptionResumePoint> resumePoints) {
        requireNonNull(resumePoints, "No resumePoints provided");
        var                     saves       = new ArrayList<DeferredGapRecording.ResumePointSave>(resumePoints.size());
        var                     savable     = new ArrayList<SubscriptionResumePoint>(resumePoints.size());
        GapsNotRecordedException notRecorded = null;
        RuntimeException         waitRanOut  = null;
        try {
            for (var resumePoint : resumePoints) {
                // Whether a resume point is changed is not stable - its subscription may advance it any moment. The
                // delegate reads it again later, so one found unchanged here must not reach it: it would be written,
                // should it advance meanwhile, with no save window open for its gaps
                if (!resumePoint.isChanged()) {
                    continue;
                }
                if (waitRanOut != null) {
                    notRecorded = GapsNotRecordedException.adding(notRecorded, resumePoint,
                                                                  new IllegalStateException("not tried, as waiting for the gaps of an earlier resume point of this save to be recorded ran out or was interrupted",
                                                                                            waitRanOut));
                    continue;
                }
                try {
                    saves.add(deferredGapRecording.beforeResumePointSave(resumePoint.getSubscriberId(), resumePoint.getAggregateType()));
                    savable.add(resumePoint);
                } catch (RuntimeException e) {
                    notRecorded = GapsNotRecordedException.adding(notRecorded, resumePoint, e);
                    if (isWaitThatRanOutOrWasInterrupted(e)) {
                        waitRanOut = e;
                    }
                }
            }
            if (!savable.isEmpty()) {
                delegate.saveResumePoints(savable);
            }
        } catch (RuntimeException e) {
            if (notRecorded != null) {
                e.addSuppressed(notRecorded);
            }
            throw e;
        } finally {
            saves.forEach(DeferredGapRecording.ResumePointSave::close);
        }
        if (notRecorded != null) {
            throw notRecorded;
        }
    }

    private static boolean isWaitThatRanOutOrWasInterrupted(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The gaps kept in memory for one or more resume points could not be recorded, so those resume points were not saved
     * (they stay {@link SubscriptionResumePoint#isChanged() changed}); the other resume points of the same call were. The
     * cause is the first resume point's failure - so an unreachable database is classified as one
     * ({@code IOExceptionUtil.isIOException} reads the root cause) - and each further failure is suppressed
     */
    static final class GapsNotRecordedException extends RuntimeException {
        private GapsNotRecordedException(String message, Throwable cause) {
            super(message, cause);
        }

        static GapsNotRecordedException adding(GapsNotRecordedException earlier, SubscriptionResumePoint resumePoint, RuntimeException failure) {
            var message = "Could not record the gaps kept in memory of [" + resumePoint.getSubscriberId() + "-" + resumePoint.getAggregateType() +
                    "] - its resume point " + resumePoint.getResumeFromAndIncluding() + " was not saved: " + failure.getMessage();
            if (earlier == null) {
                return new GapsNotRecordedException(message, failure);
            }
            earlier.addSuppressed(new GapsNotRecordedException(message, failure));
            return earlier;
        }
    }

    @Override
    public List<SubscriptionResumePoint> findAllResumePoints() {
        return delegate.findAllResumePoints();
    }

    @Override
    public List<SubscriptionResumePoint> findAllResumePoints(long startIndex, long pageSize) {
        return delegate.findAllResumePoints(startIndex, pageSize);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
