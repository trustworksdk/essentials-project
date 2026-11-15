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

import java.util.TreeMap;

/**
 * What one subscription has handled (design phase 3 §3.2): a prefix {@code P} - every existing order at or below it is
 * handled - plus a run-length set of the orders handled above {@code P}. Memory only, delivery thread only.
 * <p>
 * Every source of rows goes through one rule: a row at or below {@code P}, or in a run, is dropped as already handled;
 * otherwise it is handled and {@link #add(long) added}. The runs decide nothing about holes; their size is the number of
 * gaps above {@code P}, not the number of orders handled.
 */
final class HandledSet {
    private long                       prefix;
    /** Run start to run end, both inclusive, every run above {@link #prefix}, none adjacent to another */
    private final TreeMap<Long, Long>  runs = new TreeMap<>();

    /**
     * @param prefix {@code P}: {@code resume_from - 1}
     */
    HandledSet(long prefix) {
        this.prefix = prefix;
    }

    /** @return {@code P} */
    long prefix() {
        return prefix;
    }

    /** @return {@code P + 1}, the resume point (§4.7) */
    long resumeFrom() {
        return prefix + 1;
    }

    /** @return whether {@code globalOrder} is handled already */
    boolean contains(long globalOrder) {
        if (globalOrder <= prefix) {
            return true;
        }
        var run = runs.floorEntry(globalOrder);
        return run != null && run.getValue() >= globalOrder;
    }

    /**
     * Mark {@code globalOrder} handled. A no-op at or below {@code P}.
     */
    void add(long globalOrder) {
        if (contains(globalOrder)) {
            return;
        }
        var start = globalOrder;
        var end   = globalOrder;
        var below = runs.floorEntry(globalOrder - 1);
        if (below != null && below.getValue() == globalOrder - 1) {
            start = below.getKey();
            runs.remove(below.getKey());
        }
        var above = runs.get(globalOrder + 1);
        if (above != null) {
            end = above;
            runs.remove(globalOrder + 1);
        }
        runs.put(start, end);
    }

    /**
     * Raise {@code P} to {@code newPrefix} if higher, pruning the runs at or below it. Never lowers {@code P}: what
     * {@code P} claimed stays true.
     */
    void advanceTo(long newPrefix) {
        if (newPrefix <= prefix) {
            return;
        }
        prefix = newPrefix;
        while (!runs.isEmpty() && runs.firstKey() <= prefix) {
            var first = runs.pollFirstEntry();
            if (first.getValue() > prefix) {
                runs.put(prefix + 1, first.getValue());
                break;
            }
        }
    }

    /**
     * Reposition at {@code newPrefix} and forget the runs: a superseding reset (§3.6), where what was handled no
     * longer counts
     */
    void reset(long newPrefix) {
        prefix = newPrefix;
        runs.clear();
    }

    /** @return how many runs are held above {@code P} */
    int runCount() {
        return runs.size();
    }

    /** @return the highest order handled, {@code P} if no run is held */
    long highest() {
        return runs.isEmpty() ? prefix : runs.lastEntry().getValue();
    }
}
