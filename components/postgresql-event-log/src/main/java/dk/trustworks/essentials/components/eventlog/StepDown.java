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

/**
 * The step-down page size of an in-transaction subscription (design {@code docs/archive/event-store-engine-phase4.md}
 * §4.2). The size starts at {@code pageSize}. A page of size {@code n} over {@code [a, last]} that fails with a handler
 * failure makes {@code [a, last]} the <b>suspect range</b> and halves the size (at least 1). A page that commits keeps the
 * size while it starts inside the suspect range; once a page starts above it, the range is forgotten and the size doubles
 * after each committed page, up to {@code pageSize}. Isolating one failing event in a page of 100 so costs at most
 * {@code ceil(log2 100) = 7} failed pages.
 * <p>
 * Delivery-thread state, kept across a detach, memory only: a restart starts at full size.
 */
final class StepDown {
    private final int     maxSize;
    private final boolean growInsideSuspectRange;
    private int           size;
    private long          suspectLow  = Long.MIN_VALUE;
    private long          suspectHigh = Long.MIN_VALUE;
    private boolean       suspect;

    /**
     * @param maxSize                {@link EventLogSettings#pageSize()}
     * @param growInsideSuspectRange the {@link WatermarkMutant#GROW_INSIDE_SUSPECT_RANGE} mutant: grow after every
     *                               committed page
     */
    StepDown(int maxSize, boolean growInsideSuspectRange) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        this.maxSize = maxSize;
        this.growInsideSuspectRange = growInsideSuspectRange;
        this.size = maxSize;
    }

    /** @return the size of the next page */
    int size() {
        return size;
    }

    /** @return whether a suspect range is set */
    boolean hasSuspectRange() {
        return suspect;
    }

    long suspectLow() {
        return suspectLow;
    }

    long suspectHigh() {
        return suspectHigh;
    }

    /**
     * A page of {@code pageSize} events over {@code [first, last]} failed with a handler failure
     */
    void failed(long first, long last, int pageSize) {
        suspect = true;
        suspectLow = first;
        suspectHigh = last;
        size = Math.max(1, pageSize / 2);
    }

    /**
     * A page over {@code [first, last]} committed
     */
    void committed(long first, long last) {
        if (suspect && first <= suspectHigh) {
            if (growInsideSuspectRange) {
                grow();
            }
            return;
        }
        suspect = false;
        grow();
    }

    /** Back to full size, no suspect range: a reset */
    void reset() {
        suspect = false;
        size = maxSize;
    }

    private void grow() {
        size = (int) Math.min(maxSize, size * 2L);
    }
}
