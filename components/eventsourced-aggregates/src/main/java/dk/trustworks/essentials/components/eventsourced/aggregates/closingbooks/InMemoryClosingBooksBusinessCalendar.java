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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * A thread-safe, in-memory {@link ClosingBooksBusinessCalendar} that the application feeds with the business days
 * it learns have closed.
 * <p>
 * Each close is recorded as {@code businessDate -> nextBusinessDate} with {@link #recordClose(LocalDate, LocalDate)}:
 * <ul>
 *     <li>the current business date is the latest {@code nextBusinessDate} recorded, so it never moves backwards,
 *     whatever order closes arrive in;</li>
 *     <li>recording the same close again changes nothing, so redelivered signals are harmless;</li>
 *     <li>a late close for an older day fills its place in {@link #businessDaysClosedBetween(LocalDate, LocalDate)};</li>
 *     <li>a close for a date already recorded with a different next date is a source error: the first one is kept,
 *     the conflict is logged and counted ({@link #conflictingCloses()}).</li>
 * </ul>
 * Readers see an immutable snapshot through a {@code volatile} field, so evaluation on the hot path takes no lock;
 * writers are serialised. Persisting closes is the application's concern: on start-up, replay the recorded closes into
 * this calendar before serving commands (until then {@link #currentBusinessDate()} is empty and no books roll), and on
 * every instance keep it fed as new closes are recorded.
 */
public final class InMemoryClosingBooksBusinessCalendar implements ClosingBooksBusinessCalendar {
    private static final Logger log = LoggerFactory.getLogger(InMemoryClosingBooksBusinessCalendar.class);

    private final Object     writeLock         = new Object();
    private final AtomicLong conflictingCloses = new AtomicLong();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * Records that {@code businessDate} closed and {@code nextBusinessDate} opened.
     *
     * @param businessDate     the business date that closed
     * @param nextBusinessDate the business date the close opened; must be after {@code businessDate}
     * @return {@code true} if the close was new, {@code false} if it was already recorded or conflicted with a recorded
     * one
     */
    public boolean recordClose(LocalDate businessDate, LocalDate nextBusinessDate) {
        requireNonNull(businessDate, "No businessDate provided");
        requireNonNull(nextBusinessDate, "No nextBusinessDate provided");
        requireTrue(nextBusinessDate.isAfter(businessDate),
                    "nextBusinessDate " + nextBusinessDate + " must be after businessDate " + businessDate);
        synchronized (writeLock) {
            var current  = snapshot;
            var recorded = current.closes.get(businessDate);
            if (recorded != null) {
                if (!recorded.equals(nextBusinessDate)) {
                    conflictingCloses.incrementAndGet();
                    log.warn("Conflicting close of business date {}: recorded with next business date {}, now reported with {}. Keeping the first",
                             businessDate, recorded, nextBusinessDate);
                }
                return false;
            }
            var closes = new TreeMap<>(current.closes);
            closes.put(businessDate, nextBusinessDate);
            snapshot = Snapshot.of(closes);
            return true;
        }
    }

    @Override
    public Optional<LocalDate> currentBusinessDate() {
        return Optional.ofNullable(snapshot.currentBusinessDate);
    }

    @Override
    public long businessDaysClosedBetween(LocalDate from, LocalDate to) {
        requireNonNull(from, "No from provided");
        requireNonNull(to, "No to provided");
        if (!to.isAfter(from)) {
            return 0;
        }
        var dates = snapshot.closedDates;
        return insertionPoint(dates, to) - insertionPoint(dates, from);
    }

    /**
     * @return the latest business date recorded as closed, or empty when none is
     */
    public Optional<LocalDate> latestClosedBusinessDate() {
        var dates = snapshot.closedDates;
        return dates.length == 0 ? Optional.empty() : Optional.of(dates[dates.length - 1]);
    }

    /**
     * Closes that were never recorded although a recorded close names their date as its next business date: the gaps
     * in the chain of closes. The current business date is still right with gaps; only
     * {@link #businessDaysClosedBetween(LocalDate, LocalDate)} under-counts until the missing closes arrive.
     *
     * @return the business dates whose close is missing, oldest first
     */
    public List<LocalDate> missingCloses() {
        var current = snapshot;
        var missing = new ArrayList<LocalDate>();
        current.closes.forEach((businessDate, next) -> {
            if (!next.equals(current.currentBusinessDate) && !current.closes.containsKey(next)) {
                missing.add(next);
            }
        });
        missing.sort(Comparator.naturalOrder());
        return missing;
    }

    /**
     * @return how many closes were reported with a next business date different from the one already recorded
     */
    public long conflictingCloses() {
        return conflictingCloses.get();
    }

    /**
     * @return how many closes are recorded
     */
    public int recordedCloses() {
        return snapshot.closedDates.length;
    }

    private static int insertionPoint(LocalDate[] sorted, LocalDate date) {
        var index = Arrays.binarySearch(sorted, date);
        return index >= 0 ? index : -index - 1;
    }

    @Override
    public String toString() {
        var current = snapshot;
        return "InMemoryClosingBooksBusinessCalendar{currentBusinessDate=" + current.currentBusinessDate
                + ", recordedCloses=" + current.closedDates.length + "}";
    }

    private record Snapshot(NavigableMap<LocalDate, LocalDate> closes, LocalDate[] closedDates, LocalDate currentBusinessDate) {
        static final Snapshot EMPTY = new Snapshot(Collections.emptyNavigableMap(), new LocalDate[0], null);

        static Snapshot of(TreeMap<LocalDate, LocalDate> closes) {
            var current = closes.values().stream().max(Comparator.naturalOrder()).orElse(null);
            return new Snapshot(Collections.unmodifiableNavigableMap(closes),
                                closes.keySet().toArray(LocalDate[]::new),
                                current);
        }
    }
}
