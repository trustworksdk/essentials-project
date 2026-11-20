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
package dk.trustworks.essentials.components.eventlog;

import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The engine's own wrapper around the application's {@link EventLogObserver}: every callback runs on the reader loop's
 * thread, the lease keeper's thread or a subscription's delivery thread, and a callback that throws would otherwise end
 * that thread or skip the work after the call (an attach reply never completed, a lease pass never finished). A
 * {@link RuntimeException} from the delegate is logged - at WARN at most once a minute, at DEBUG otherwise - and
 * swallowed. {@link Error}s are not caught here; the threads handle those themselves.
 * <p>
 * No cost on the happy path beyond one extra call: the try block costs nothing until something throws.
 */
final class SafeEventLogObserver implements EventLogObserver {
    private static final Logger log            = LoggerFactory.getLogger(SafeEventLogObserver.class);
    private static final long   WARN_EVERY_NANOS = Duration.ofMinutes(1).toNanos();

    private final EventLogObserver delegate;
    private final AtomicLong       lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_EVERY_NANOS);

    private SafeEventLogObserver(EventLogObserver delegate) {
        this.delegate = delegate;
    }

    /**
     * @return {@code observer} wrapped, or as is when it is {@link EventLogObserver#NONE} or already wrapped
     */
    static EventLogObserver wrap(EventLogObserver observer) {
        requireNonNull(observer, "No observer provided");
        if (observer == NONE || observer instanceof SafeEventLogObserver) {
            return observer;
        }
        return new SafeEventLogObserver(observer);
    }

    private void failed(String callback, RuntimeException e) {
        var now  = System.nanoTime();
        var last = lastWarnNanos.get();
        if (now - last >= WARN_EVERY_NANOS && lastWarnNanos.compareAndSet(last, now)) {
            log.warn("EventLogObserver {} of {} threw; the engine ignores it, but an observer must not throw (logged at most once a minute)",
                     callback, delegate.getClass().getName(), e);
        } else {
            log.debug("EventLogObserver {} of {} threw", callback, delegate.getClass().getName(), e);
        }
    }

    @Override
    public void onRead(String aggregateType, boolean combined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, Duration oldestCandidateAge) {
        try {
            delegate.onRead(aggregateType, combined, nanos, rows, outstandingHoles, outstandingCandidates, oldestCandidateAge);
        } catch (RuntimeException e) {
            failed("onRead", e);
        }
    }

    @Override
    public void onHoleRead(String aggregateType, int resolvedHoles, int lateRows) {
        try {
            delegate.onHoleRead(aggregateType, resolvedHoles, lateRows);
        } catch (RuntimeException e) {
            failed("onHoleRead", e);
        }
    }

    @Override
    public void onReaderCursor(String aggregateType, long cursor) {
        try {
            delegate.onReaderCursor(aggregateType, cursor);
        } catch (RuntimeException e) {
            failed("onReaderCursor", e);
        }
    }

    @Override
    public void onProgressSaved(String subscriber, String aggregateType, long resumeFrom) {
        try {
            delegate.onProgressSaved(subscriber, aggregateType, resumeFrom);
        } catch (RuntimeException e) {
            failed("onProgressSaved", e);
        }
    }

    @Override
    public void onError(String subscriber, String aggregateType, Throwable error) {
        try {
            delegate.onError(subscriber, aggregateType, error);
        } catch (RuntimeException e) {
            failed("onError", e);
        }
    }

    @Override
    public void onAttach(String subscriber, String aggregateType, long cursor, long watermark) {
        try {
            delegate.onAttach(subscriber, aggregateType, cursor, watermark);
        } catch (RuntimeException e) {
            failed("onAttach", e);
        }
    }

    @Override
    public void onDetach(String subscriber, String aggregateType, String reason, long resumeFrom) {
        try {
            delegate.onDetach(subscriber, aggregateType, reason, resumeFrom);
        } catch (RuntimeException e) {
            failed("onDetach", e);
        }
    }

    @Override
    public void onCatchUpRead(String subscriber, String aggregateType, boolean bridge, long nanos, int rows) {
        try {
            delegate.onCatchUpRead(subscriber, aggregateType, bridge, nanos, rows);
        } catch (RuntimeException e) {
            failed("onCatchUpRead", e);
        }
    }

    @Override
    public void onBatchTaken(String subscriber, String aggregateType, int rows, int inboxEvents) {
        try {
            delegate.onBatchTaken(subscriber, aggregateType, rows, inboxEvents);
        } catch (RuntimeException e) {
            failed("onBatchTaken", e);
        }
    }

    @Override
    public void onWaitingForWatermark(String subscriber, String aggregateType, boolean waiting, Set<String> heldBy) {
        try {
            delegate.onWaitingForWatermark(subscriber, aggregateType, waiting, heldBy);
        } catch (RuntimeException e) {
            failed("onWaitingForWatermark", e);
        }
    }

    @Override
    public void onPageCommitted(String subscriber, String aggregateType, int size) {
        try {
            delegate.onPageCommitted(subscriber, aggregateType, size);
        } catch (RuntimeException e) {
            failed("onPageCommitted", e);
        }
    }

    @Override
    public void onPageCommitted(String subscriber, String aggregateType, int size, long nanos) {
        try {
            delegate.onPageCommitted(subscriber, aggregateType, size, nanos);
        } catch (RuntimeException e) {
            failed("onPageCommitted", e);
        }
    }

    @Override
    public void onPageFailed(String subscriber, String aggregateType, int size, Throwable cause) {
        try {
            delegate.onPageFailed(subscriber, aggregateType, size, cause);
        } catch (RuntimeException e) {
            failed("onPageFailed", e);
        }
    }

    @Override
    public void onPageFailed(String subscriber, String aggregateType, int size, long nanos, Throwable cause) {
        try {
            delegate.onPageFailed(subscriber, aggregateType, size, nanos, cause);
        } catch (RuntimeException e) {
            failed("onPageFailed", e);
        }
    }

    @Override
    public void onReleased(String subscriber, String aggregateType, int rows, long receivedWatermark, int heldBack) {
        try {
            delegate.onReleased(subscriber, aggregateType, rows, receivedWatermark, heldBack);
        } catch (RuntimeException e) {
            failed("onReleased", e);
        }
    }

    @Override
    public void onParked(String subscriber, String aggregateType, long globalOrder, Long blockedBy, int attempts) {
        try {
            delegate.onParked(subscriber, aggregateType, globalOrder, blockedBy, attempts);
        } catch (RuntimeException e) {
            failed("onParked", e);
        }
    }

    @Override
    public void onHeldAggregates(String subscriber, String aggregateType, int count) {
        try {
            delegate.onHeldAggregates(subscriber, aggregateType, count);
        } catch (RuntimeException e) {
            failed("onHeldAggregates", e);
        }
    }

    @Override
    public void onCatchUpPermit(String subscriber, String aggregateType, long waitNanos, int inUse) {
        try {
            delegate.onCatchUpPermit(subscriber, aggregateType, waitNanos, inUse);
        } catch (RuntimeException e) {
            failed("onCatchUpPermit", e);
        }
    }

    @Override
    public void onFilteredRead(String subscriber, String aggregateType, long scannedOrders, int rows, long window, boolean usedIndex) {
        try {
            delegate.onFilteredRead(subscriber, aggregateType, scannedOrders, rows, window, usedIndex);
        } catch (RuntimeException e) {
            failed("onFilteredRead", e);
        }
    }

    @Override
    public void onGapsHandedOff(String subscriber, String aggregateType, int gaps, boolean toReader) {
        try {
            delegate.onGapsHandedOff(subscriber, aggregateType, gaps, toReader);
        } catch (RuntimeException e) {
            failed("onGapsHandedOff", e);
        }
    }

    @Override
    public void onFlush(List<String> aggregateTypes, int statements, long nanos) {
        try {
            delegate.onFlush(aggregateTypes, statements, nanos);
        } catch (RuntimeException e) {
            failed("onFlush", e);
        }
    }

    @Override
    public void onLeaseClaimed(String subscriber, String aggregateType, long fence) {
        try {
            delegate.onLeaseClaimed(subscriber, aggregateType, fence);
        } catch (RuntimeException e) {
            failed("onLeaseClaimed", e);
        }
    }

    @Override
    public void onLeaseLost(String subscriber, String aggregateType, String reason) {
        try {
            delegate.onLeaseLost(subscriber, aggregateType, reason);
        } catch (RuntimeException e) {
            failed("onLeaseLost", e);
        }
    }

    @Override
    public void onLeaseSuspended(String subscriber, String aggregateType) {
        try {
            delegate.onLeaseSuspended(subscriber, aggregateType);
        } catch (RuntimeException e) {
            failed("onLeaseSuspended", e);
        }
    }

    @Override
    public void onLeaseResumed(String subscriber, String aggregateType) {
        try {
            delegate.onLeaseResumed(subscriber, aggregateType);
        } catch (RuntimeException e) {
            failed("onLeaseResumed", e);
        }
    }

    @Override
    public void onLeaseOverrun(String subscriber, String aggregateType, long nanos) {
        try {
            delegate.onLeaseOverrun(subscriber, aggregateType, nanos);
        } catch (RuntimeException e) {
            failed("onLeaseOverrun", e);
        }
    }

    @Override
    public void onRenewal(int owned, int renewed, long nanos) {
        try {
            delegate.onRenewal(owned, renewed, nanos);
        } catch (RuntimeException e) {
            failed("onRenewal", e);
        }
    }

    @Override
    public void onStopped(String subscriber, String aggregateType) {
        try {
            delegate.onStopped(subscriber, aggregateType);
        } catch (RuntimeException e) {
            failed("onStopped", e);
        }
    }

    @Override
    public void onRenewalFailed(Throwable cause) {
        try {
            delegate.onRenewalFailed(cause);
        } catch (RuntimeException e) {
            failed("onRenewalFailed", e);
        }
    }
}
