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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * The lease state machine and its deadline arithmetic with an injected monotonic clock (design phase 7 §2.4, §9), and
 * §2.6's zero-row decision table
 */
class LeaseTest {
    static final long             STOP  = 7_000_000_000L;
    static final ProgressStore.Key KEY  = new ProgressStore.Key("sub", "Orders", "");
    static final Lease.Claim       CLAIM = new Lease.Claim(42, 10, 0, "FRESH", "PERIODIC");

    private final AtomicLong clock = new AtomicLong(1_000);
    private final Lease      lease = new Lease(KEY, new Lease.NewRow("FRESH", "PERIODIC", 1), "node/abcd1234", STOP, clock::get);

    @Test
    void a_new_lease_is_standby_and_wants_a_claim() {
        assertThat(lease.state()).isEqualTo(Lease.State.STANDBY);
        assertThat(lease.wantsClaim()).isTrue();
        assertThat(lease.wantsRenewal()).isFalse();
        assertThat(lease.fence()).isNull();
        assertThat(lease.pastDeadline()).isFalse();
    }

    @Test
    void a_claim_owns_the_lease_with_its_fence_and_confirms_at_the_claim_start() throws Exception {
        assertThat(lease.claimed(CLAIM, 500)).isTrue();
        assertThat(lease.state()).isEqualTo(Lease.State.OWNED);
        assertThat(lease.fence()).isEqualTo(new ProgressStore.Fence(42, "node/abcd1234"));
        assertThat(lease.confirmedAtNanos()).isEqualTo(500);
        assertThat(lease.wantsClaim()).isFalse();
        assertThat(lease.wantsRenewal()).isTrue();
        assertThat(lease.awaitClaim()).isEqualTo(CLAIM);
    }

    @Test
    void the_deadline_is_the_confirmation_plus_the_local_stop() {
        lease.claimed(CLAIM, 1_000);
        clock.set(1_000 + STOP);
        assertThat(lease.pastDeadline()).as("exactly at the deadline").isFalse();
        clock.set(1_000 + STOP + 1);
        assertThat(lease.pastDeadline()).isTrue();
        assertThat(lease.nanosPastDeadline()).isEqualTo(1);
    }

    @Test
    void past_the_deadline_the_lease_suspends_and_a_renewal_resumes_it() throws Exception {
        lease.claimed(CLAIM, 1_000);
        clock.set(1_000 + STOP - 1);
        assertThat(lease.suspendIfPastDeadline()).isFalse();
        clock.set(1_000 + STOP + 1);
        assertThat(lease.suspendIfPastDeadline()).isTrue();
        assertThat(lease.state()).isEqualTo(Lease.State.SUSPENDED);
        assertThat(lease.wantsRenewal()).as("a suspended lease is still renewed").isTrue();

        assertThat(lease.renewed(clock.get())).isTrue();
        assertThat(lease.state()).isEqualTo(Lease.State.OWNED);
        assertThat(lease.awaitNotSuspended(1_000_000)).isEqualTo(Lease.State.OWNED);
    }

    @Test
    void a_renewal_never_moves_the_confirmation_backwards() {
        lease.claimed(CLAIM, 5_000);
        lease.renewed(4_000);
        assertThat(lease.confirmedAtNanos()).isEqualTo(5_000);
        lease.renewed(6_000);
        assertThat(lease.confirmedAtNanos()).isEqualTo(6_000);
    }

    @Test
    void a_loss_after_the_claim_was_taken_is_lost_until_the_delivery_thread_goes_standby() throws Exception {
        lease.claimed(CLAIM, 1_000);
        lease.awaitClaim();
        assertThat(lease.lost("fence moved")).isTrue();
        assertThat(lease.state()).isEqualTo(Lease.State.LOST);
        assertThat(lease.lostReason()).isEqualTo("fence moved");
        assertThat(lease.wantsClaim()).isFalse();
        assertThat(lease.wantsRenewal()).isFalse();
        assertThat(lease.lost("again")).as("lost once").isFalse();
        assertThat(lease.renewed(2_000)).isFalse();
        assertThat(lease.state()).isEqualTo(Lease.State.LOST);

        lease.standby();
        assertThat(lease.state()).isEqualTo(Lease.State.STANDBY);
        assertThat(lease.fence()).isNull();
        assertThat(lease.wantsClaim()).isTrue();
    }

    @Test
    void a_loss_before_the_claim_was_taken_goes_straight_back_to_standby() {
        lease.claimed(CLAIM, 1_000);
        assertThat(lease.lost("fence moved")).isTrue();
        assertThat(lease.state()).isEqualTo(Lease.State.STANDBY);
        assertThat(lease.wantsClaim()).isTrue();
    }

    @Test
    void a_closed_lease_takes_no_claim_and_wakes_the_waiter() throws Exception {
        lease.close();
        assertThat(lease.claimed(CLAIM, 1_000)).as("the keeper releases it instead").isFalse();
        assertThat(lease.wantsClaim()).isFalse();
        assertThat(lease.awaitClaim()).isNull();
    }

    @Test
    void the_zero_row_decision_follows_its_order() {
        var fence = new ProgressStore.Fence(42, "me");
        Optional<ProgressStore.Progress> gone = Optional.empty();
        assertThat(Subscription.decide(false, gone, 0, 10, null)).isEqualTo(Subscription.ConflictDecision.RECREATE);
        assertThat(Subscription.decide(true, gone, 0, 10, fence)).isEqualTo(Subscription.ConflictDecision.STOP);

        // The fence or owner first, even when the epoch moved too: a new owner handles the reset
        assertThat(Subscription.decide(true, row(10, 1, "me", 43L), 0, 10, fence)).isEqualTo(Subscription.ConflictDecision.LOST);
        assertThat(Subscription.decide(true, row(10, 0, "other", 42L), 0, 10, fence)).isEqualTo(Subscription.ConflictDecision.LOST);
        assertThat(Subscription.decide(true, row(10, 0, null, 42L), 0, 10, fence)).as("released by someone else").isEqualTo(Subscription.ConflictDecision.LOST);

        assertThat(Subscription.decide(true, row(5, 1, "me", 42L), 0, 10, fence)).isEqualTo(Subscription.ConflictDecision.RESET);
        assertThat(Subscription.decide(false, row(5, 1, null, null), 0, 10, null)).isEqualTo(Subscription.ConflictDecision.RESET);
        assertThat(Subscription.decide(true, row(7, 0, "me", 42L), 0, 10, fence)).isEqualTo(Subscription.ConflictDecision.MOVED);
        assertThat(Subscription.decide(false, row(7, 0, null, null), 0, 10, null)).isEqualTo(Subscription.ConflictDecision.MOVED);
        assertThat(Subscription.decide(true, row(10, 0, "me", 42L), 0, 10, fence)).isEqualTo(Subscription.ConflictDecision.NONE);
        assertThat(Subscription.decide(false, row(10, 0, "x", 9L), 0, 10, null)).as("a non-exclusive row's lease columns are ignored")
                                                                                 .isEqualTo(Subscription.ConflictDecision.NONE);
    }

    private static Optional<ProgressStore.Progress> row(long resumeFrom, long epoch, String owner, Long fence) {
        return Optional.of(new ProgressStore.Progress(resumeFrom, epoch, "FRESH", "PERIODIC", true, owner, fence));
    }

    @Test
    void an_exclusive_spec_has_the_empty_instance_name_and_a_non_exclusive_one_a_non_blank_one() {
        PeriodicHandler handler = event -> {
        };
        assertThatThrownBy(() -> new Subscription.SubscriptionSpec(new ProgressStore.Key("s", "Orders", " "), 1, handler, TestSeams.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Subscription.SubscriptionSpec(new ProgressStore.Key("s", "Orders", "node-1"), 1, ConsistencyMode.FRESH,
                                                                   Subscription.ProgressModel.PERIODIC, handler, null, TestSeams.NONE, EventTypeFilter.all(), true))
                .isInstanceOf(IllegalArgumentException.class);
        var exclusive = new Subscription.SubscriptionSpec(new ProgressStore.Key("s", "Orders", "node-1"), 1, handler, TestSeams.NONE).asExclusive();
        assertThat(exclusive.exclusive()).isTrue();
        assertThat(exclusive.key().instanceName()).isEmpty();
    }
}
