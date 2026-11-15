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
 * An append lost its optimistic-concurrency check: the stream's head was not at the expected event order, or another
 * writer created the stream first. No {@code global_order} value was consumed. The caller's transaction holds whatever
 * the other requests of the same {@link EventLog#append} call wrote, and must be rolled back.
 * <p>
 * Also thrown when the {@code UNIQUE(aggregate_id, event_order)} backstop fires, which only happens when a stream's
 * head row is out of step with its events (an external writer); the transaction is then already aborted and the
 * actual event order is unknown.
 */
public final class StreamAppendConflictException extends RuntimeException {
    private final String aggregateType;
    private final String aggregateId;
    private final Long   expectedEventOrder;
    private final Long   actualEventOrder;

    /**
     * @param aggregateType      the aggregate type
     * @param aggregateId        the stream
     * @param expectedEventOrder what the caller expected, or {@code null} if it gave no expectation
     * @param actualEventOrder   the stream's last event order as committed when the conflict was found, {@code -1} for no
     *                           stream, or {@code null} when unknown
     */
    public StreamAppendConflictException(String aggregateType, String aggregateId, Long expectedEventOrder, Long actualEventOrder) {
        super("Append to aggregate type '" + aggregateType + "' stream '" + aggregateId + "' expected last event order " + expectedEventOrder +
                      " but it was " + (actualEventOrder == null ? "unknown" : actualEventOrder));
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.expectedEventOrder = expectedEventOrder;
        this.actualEventOrder = actualEventOrder;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    /**
     * @return the expected last event order, or {@code null}
     */
    public Long expectedEventOrder() {
        return expectedEventOrder;
    }

    /**
     * @return the actual last event order, {@code -1} for no stream, or {@code null} when unknown
     */
    public Long actualEventOrder() {
        return actualEventOrder;
    }
}
