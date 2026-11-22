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

import java.util.concurrent.CompletableFuture;

/**
 * A running subscription, returned by {@link EventLog#subscribe}
 */
public interface SubscriptionHandle {
    /** @return what was subscribed */
    SubscriptionDefinition definition();

    /**
     * Stop delivering: a periodic subscription saves its resume point once more, an exclusive one releases its lease.
     * Idempotent.
     */
    void stop();

    /** @return whether the delivery thread runs */
    boolean isRunning();

    /** @return a local snapshot of the subscription's state in this event log */
    SubscriptionStatus status();

    /**
     * Retry the aggregate parked at {@code headGlobalOrder} on the delivery thread, between pages: the head and the events
     * held behind it are handed to the handler in order and their parked rows deleted, in one fenced, epoch-guarded
     * transaction per serve. One serve hands over at most {@link ErrorSettings#pageSize()} events; the next unhandled
     * one then heads the aggregate with a retry request of its own, served at the next parked check. If an event held
     * behind the head fails (a {@code ParkEventException} included), the events before it are applied, it becomes the
     * aggregate's head with one attempt counted, and the events after it wait behind it: retry or discard that one next.
     * Only a failure of the head itself leaves everything as it was.
     *
     * @return {@code true} once applied; {@code false} if it failed, the head is not parked, or a reset intervened;
     * completed exceptionally if the subscription is not running
     */
    CompletableFuture<Boolean> retryParked(long headGlobalOrder);

    /**
     * As {@link #retryParked(long)}, but the head is deleted without being handed to the handler; the events held behind it are applied
     * (a held event that fails becomes the new head, as on a retry)
     */
    CompletableFuture<Boolean> discardParked(long headGlobalOrder);
}
