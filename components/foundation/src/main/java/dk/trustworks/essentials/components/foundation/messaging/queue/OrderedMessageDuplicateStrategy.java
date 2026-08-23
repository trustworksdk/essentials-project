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

package dk.trustworks.essentials.components.foundation.messaging.queue;

/**
 * What to do when two {@link OrderedMessage}s share both a {@link OrderedMessage#getKey() key} and an
 * {@link OrderedMessage#getOrder() order}.
 *
 * <h2>Why this needs a decision at all</h2>
 * The per-key barrier that serialises ordered delivery asks whether any row for the key has a <em>strictly</em>
 * lower {@code key_order}. Two messages carrying the same key and the same order therefore never block each
 * other, both are eligible at once, and per-key ordering — the guarantee the whole ordered-message feature
 * exists to provide — silently does not hold for them. Nothing in the schema prevents it today.
 * <p>
 * That is demonstrated rather than asserted: the negative control in
 * {@code PostgresqlOrderedMessagesMultiNodeIT} relies on exactly this to prove its overlap detector can fire.
 *
 * <h2>Why {@link #REJECT} is the default</h2>
 * Every ordered message the framework itself produces is duplicate-free by construction. `AbstractEventProcessor`,
 * `ViewEventProcessor` and `EventStoreSubscriptionManager` all use the aggregate id as the key and the event's
 * {@code EventOrder} as the order, and an {@code EventOrder} is unique within its stream. So the default cannot
 * break any path the framework drives.
 * <p>
 * The exposure is application code that derives the order itself from something not unique — a constant, or a
 * timestamp with collisions. For those, rejecting is the correct outcome: the alternative is ordered delivery
 * that quietly is not ordered. And for the common case of an at-least-once upstream re-publishing an event
 * already queued, rejection <em>is</em> idempotent enqueue, which is a feature rather than merely a guard.
 *
 * @see #REJECT
 * @see #ALLOW
 */
public enum OrderedMessageDuplicateStrategy {
    /**
     * Refuse the duplicate. A unique index over {@code (queue_name, key, key_order)} for ordered messages makes
     * the second enqueue fail rather than silently breaking that key's ordering.
     * <p>
     * <b>Migration note:</b> the index cannot be created on a table that already contains duplicates, and a
     * storage implementation must fail loudly rather than continue unprotected — see the strategy's wiring in
     * {@code PostgresqlDurableQueues}. An operator who genuinely has duplicates and wants to keep them must
     * choose {@link #ALLOW} explicitly.
     */
    REJECT,

    /**
     * Permit the duplicate, which is the behaviour before this setting existed.
     * <p>
     * <b>Ordering does not hold for the affected key.</b> Two messages sharing a key and an order are eligible
     * simultaneously and may be handled concurrently or out of sequence, on one node or across a cluster. Choose
     * this only when the ordering guarantee is not relied upon for those keys, or to keep an existing deployment
     * running while its duplicates are resolved.
     */
    ALLOW
}
