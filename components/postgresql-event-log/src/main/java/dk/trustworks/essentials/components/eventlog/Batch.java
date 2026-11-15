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

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One read of the shared tail reader as offered to every attached subscription (design phase 3 §1.1, §2.3): the rows
 * the read published, in {@code global_order} order, and the reader's watermark after that read. Immutable; the same
 * instance goes to every attached inbox.
 *
 * @param rows      the published rows; empty for a watermark-only batch
 * @param watermark the reader's watermark after the read; every order at or below it reached the inbox no later than
 *                  this batch (F2), or {@link Long#MIN_VALUE} if unknown (only a mutant publishes one then)
 */
record Batch(List<StoredEvent> rows, long watermark) {
    Batch {
        rows = List.copyOf(requireNonNull(rows, "No rows provided"));
    }

    /** @return how many events the batch holds, which is what an inbox's bound counts */
    int size() {
        return rows.size();
    }

    boolean watermarkOnly() {
        return rows.isEmpty();
    }
}
