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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 §7.1 and §7.3, the attached side of a filtered subscription: the filtered {@code FRESH} bridge keeps the attach
 * holes unhandled (I13), the detach after an overflow resumes below the unhandled matching rows (F5), and a slow
 * rare-type subscription's inbox counts matching rows only (§2.4). The filtered variants of {@code EventLogAttachIT},
 * {@code EventLogDetachIT} and {@code EventLogSlowSubscriptionIT}, kept here so the phase-3 ITs stay as they were.
 */
class EventLogFilteredAttachedIT extends AbstractSubscriptionIT {
    private static final EventTypeFilter ONLY_X = EventTypeFilter.of("X");

    // ---- I13: the filtered FRESH bridge ----

    @Test
    void a_late_commit_into_an_attach_hole_reaches_a_filtered_bridge() throws Exception {
        assertThat(attachHoleCommittedBetweenBridgePages()).isTrue();
    }

    @Test
    void must_fail_a_filtered_bridge_marking_the_attach_holes_handled_drops_the_late_commit() throws Exception {
        assertThat(attachHoleCommittedBetweenBridgePages(WatermarkMutant.FILTERED_BRIDGE_MARKS_HOLES)).isFalse();
    }

    /**
     * A long writer holds {@code X} at attach hole {@code h}; non-matching rows and 30 {@code X} fill {@code (W, C]}; the
     * bridge pages 10 rows at a time, and {@code h} commits at the first {@code betweenBridgePages}
     *
     * @return whether {@code h} was delivered
     */
    private boolean attachHoleCommittedBetweenBridgePages(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withCatchUpPageSize(10));
        subscribe("warm", event -> {
        }, TestSeams.NONE, 1);
        appendTyped("y-0", "Y", 5);
        var held = openAppend(AppendRequest.withoutExpectedOrder(TYPE, "x-h", events("X", 1)));
        var last = 0L;
        for (var i = 0; i < 30; i++) {
            appendTyped("y-" + i % 3, "Y", 4);
            last = appendTyped("x", "X", 1);
        }
        var head = last;
        awaitTrue(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE).state().cursor() >= head
                && eventLog.reader(TYPE).state().holes().contains(held.globalOrder()));
        var committed = new AtomicBoolean();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void betweenBridgePages(String subscriber, long lowerBound) throws Exception {
                if (committed.compareAndSet(false, true)) {
                    held.commit();
                }
            }
        };
        var recorder = new Recorder();
        var tested   = subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tested", TYPE, "node-1"), 1, recorder, new TestSeams(Set.of(mutants), hooks, null))
                                         .withFilter(ONLY_X));
        awaitDelivered(recorder, head);
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        assertThat(committed).as("the bridge took more than one page").isTrue();
        assertThat(recorder.delivered).allMatch(event -> event.eventType().equals("X"));
        return deliveredWithin(recorder, held.globalOrder());
    }

    // ---- F5: detach after an overflow ----

    @Test
    void a_filtered_subscription_detached_by_an_overflow_delivers_every_matching_event_once_in_order() throws Exception {
        var outcome = detachAfterOverflow();
        assertThat(outcome.missed()).isEmpty();
        assertThat(outcome.duplicates()).isEmpty();
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    void must_fail_a_filtered_detach_resuming_at_the_watermark_loses_the_unhandled_matching_rows() throws Exception {
        assertThat(detachAfterOverflow(WatermarkMutant.DETACH_RESUMES_AT_WATERMARK).missed()).isNotEmpty();
    }

    record DetachOutcome(Set<Long> missed, List<Long> duplicates, List<String> violations) {
    }

    /**
     * Inbox of 10. The handler blocks on matching {@code g1}; matching {@code g2…} with non-matching rows between them
     * fill the inbox until a batch overflows it; then the handler is released
     */
    private DetachOutcome detachAfterOverflow(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(10).withSubscriptionBufferSize(10));
        var recorder = new Recorder();
        recorder.blockAtAggregate = "x-block";
        var tested = subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tested", TYPE, "node-1"), 1, recorder, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null))
                                       .withFilter(ONLY_X));
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        // One read: g1 (blocks), then matching rows with non-matching ones between them, all in the batch being worked through
        var requests = new ArrayList<AppendRequest>();
        requests.add(AppendRequest.withoutExpectedOrder(TYPE, "x-block", events("X", 1)));
        for (var n = 0; n < 4; n++) {
            requests.add(AppendRequest.withoutExpectedOrder(TYPE, "y", events("Y", 1)));
            requests.add(AppendRequest.withoutExpectedOrder(TYPE, "x", events("X", 1)));
        }
        var matching = new TreeSet<Long>();
        for (var result : appendCommitted(eventLog, requests.toArray(AppendRequest[]::new))) {
            if (!result.aggregateId().equals("y")) {
                matching.add(result.events().getFirst().globalOrder());
            }
        }
        recorder.blocked.await();
        var i = 0;
        while (tested.inbox() != null && tested.inbox().closeReason() == null && i < 200) {
            appendTyped("y", "Y", 2);
            matching.add(appendTyped("x", "X", 1));
            i++;
            var target = matching.last();
            awaitTrue(() -> eventLog.reader(TYPE).state().cursor() >= target);
        }
        assertThat(tested.inbox() == null || tested.inbox().closeReason() == Inbox.CloseReason.OVERFLOW).as("the inbox overflowed").isTrue();
        recorder.release.countDown();
        var tail = appendTyped("x", "X", 1);
        matching.add(tail);
        deliveredWithin(recorder, tail);
        awaitReads(2);
        var missed = new TreeSet<>(matching);
        missed.removeAll(recorder.deliveredOrders());
        var seen       = new HashSet<Long>();
        var duplicates = new ArrayList<Long>();
        recorder.delivered.forEach(event -> {
            if (!seen.add(event.globalOrder()) || !event.eventType().equals("X")) {
                duplicates.add(event.globalOrder());
            }
        });
        return new DetachOutcome(missed, duplicates, perAggregateOrderViolations(recorder.delivered));
    }

    // ---- §2.4: a slow rare-type subscription ----

    @Test
    void a_slow_rare_type_subscription_does_not_overflow_while_its_matching_backlog_is_under_the_bound() throws Exception {
        assertThat(rareTypeOverflowed()).isFalse();
    }

    @Test
    void must_fail_an_inbox_counting_unmatched_rows_overflows() throws Exception {
        assertThat(rareTypeOverflowed(WatermarkMutant.INBOX_COUNTS_UNMATCHED)).isTrue();
    }

    /** Inbox of 10; the handler blocks on the first {@code X}; 100 non-matching rows and 5 {@code X} follow in 20 reads */
    private boolean rareTypeOverflowed(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(10).withSubscriptionBufferSize(10));
        var recorder = new Recorder();
        recorder.blockAtAggregate = "x-block";
        var tested = subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tested", TYPE, "node-1"), 1, recorder, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null))
                                       .withFilter(ONLY_X));
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        var matching = new TreeSet<Long>();
        matching.add(appendTyped("x-block", "X", 1));
        recorder.blocked.await();
        var inbox = tested.inbox();
        for (var round = 0; round < 20; round++) {
            appendTyped("y", "Y", 5);
            if (round % 4 == 0) {
                matching.add(appendTyped("x", "X", 1));
            }
            var head = queryLong("SELECT max(global_order) FROM " + tables.eventTable());
            awaitTrue(() -> eventLog.reader(TYPE).state().cursor() >= head);
        }
        awaitReads(2);
        var overflowed = inbox.closeReason() == Inbox.CloseReason.OVERFLOW;
        recorder.release.countDown();
        awaitDelivered(recorder, matching.stream().mapToLong(Long::longValue).toArray());
        return overflowed;
    }

    private long appendTyped(String aggregateId, String eventType, int count) throws SQLException {
        return appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events(eventType, count))).getFirst().events().getFirst().globalOrder();
    }
}
