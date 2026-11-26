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

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@code CONTIGUOUS} subscription's rows received above its received prefix {@code R}, sorted by
 * {@code global_order}. Rows move to the pending list only when a received watermark covers
 * them; rows a deferred-bridge read returns are dropped from here. Memory only, delivery thread only.
 * <p>
 * Each row remembers the inbox that counts it, so whoever removes it can release it there.
 */
final class HeldBack {
    /** A received row and the inbox that counts it until released, {@code null} if none does */
    record Entry(StoredEvent row, Inbox countedIn) {
        Entry {
            requireNonNull(row, "No row provided");
        }

        long globalOrder() {
            return row.globalOrder();
        }

        /** Stop counting this row in its inbox */
        void release() {
            if (countedIn != null) {
                countedIn.release(1);
            }
        }
    }

    private final TreeMap<Long, Entry> rows = new TreeMap<>();

    /**
     * Hold {@code entry}. A row already held is not held twice: the newcomer is released.
     */
    void add(Entry entry) {
        requireNonNull(entry, "No entry provided");
        var previous = rows.putIfAbsent(entry.globalOrder(), entry);
        if (previous != null) {
            entry.release();
        }
    }

    /**
     * Remove and return, in {@code global_order} order, every row at or below {@code watermark}: they move to pending
     */
    List<Entry> releaseUpTo(long watermark) {
        var head     = rows.headMap(watermark, true);
        var released = new ArrayList<>(head.values());
        head.clear();
        return released;
    }

    /**
     * Drop every row at or below {@code order} (a range read returned them), releasing each in its inbox
     *
     * @return how many were dropped
     */
    int dropUpTo(long order) {
        var head    = rows.headMap(order, true);
        var dropped = head.size();
        head.values().forEach(Entry::release);
        head.clear();
        return dropped;
    }

    /** Forget every row without releasing it: a detach, whose inbox is dropped with them */
    void clear() {
        rows.clear();
    }

    int size() {
        return rows.size();
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /** @return the lowest held order, or {@code null} */
    Long lowest() {
        return rows.isEmpty() ? null : rows.firstKey();
    }
}
