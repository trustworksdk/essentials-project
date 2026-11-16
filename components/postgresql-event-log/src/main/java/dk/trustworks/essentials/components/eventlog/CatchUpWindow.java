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

import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * The scan window of one subscription's filtered reads (design phase 5 §3.2), and the density switch that keeps the
 * type index out of a read for a common type. Delivery thread only; no SQL.
 * <ul>
 *     <li>A read scans {@code (lower, min(W, lower + window)]}. A full page keeps the window; a short page doubles it,
 *     at most {@code max}; a cancelled statement quarters it, at least the page size.</li>
 *     <li>Density is {@code rows / orders scanned} of a page: above {@link #DENSE} the reads use the primary-key walk
 *     ({@code event_type + 0}) and issue no skip-ahead probe; below {@link #SPARSE} they use the plain form again.</li>
 * </ul>
 */
final class CatchUpWindow {
    /** Above this share of matching rows, the type index is kept out of the read (§3.2; the gate places it) */
    static final double DENSE  = 0.25;
    /** Below this share, the plain form is used again */
    static final double SPARSE = 0.05;

    private final long initial;
    private final long max;
    private final int  pageSize;
    private long       window;
    private boolean    dense;

    CatchUpWindow(long initial, long max, int pageSize) {
        requireTrue(initial > 0 && max >= initial && pageSize > 0, "window bounds");
        this.initial = initial;
        this.max = max;
        this.pageSize = pageSize;
        this.window = initial;
    }

    /** @return the current window in orders */
    long window() {
        return window;
    }

    /** @return the ceiling of the next read: {@code min(watermark, lower + window)} */
    long ceiling(long lower, long watermark) {
        var upper = lower > Long.MAX_VALUE - window ? Long.MAX_VALUE : lower + window;
        return Math.min(watermark, upper);
    }

    /**
     * A read of {@code (lower, ceiling]} returned {@code rows}; {@code full} if it filled the page
     *
     * @param scanned the orders the read covered: {@code ceiling - lower} for a short page, {@code last - lower} for a full one
     */
    void afterRead(boolean full, int rows, long scanned) {
        if (!full) {
            window = window > max / 2 ? max : Math.min(max, window * 2);
        }
        if (scanned > 0 && (full || rows > 0)) {
            var density = (double) rows / scanned;
            if (density > DENSE) {
                dense = true;
            } else if (density < SPARSE) {
                dense = false;
            }
        }
    }

    /** The statement was cancelled (SQLState {@code 57014}): retry the same lower bound with a quarter of the window */
    void afterCancel() {
        window = Math.max(window / 4, pageSize);
    }

    /** @return whether the reads keep the type index out ({@code event_type + 0}) and skip the probe */
    boolean dense() {
        return dense;
    }

    /** Start over: a reposition or detach */
    void reset() {
        window = initial;
        dense = false;
    }
}
