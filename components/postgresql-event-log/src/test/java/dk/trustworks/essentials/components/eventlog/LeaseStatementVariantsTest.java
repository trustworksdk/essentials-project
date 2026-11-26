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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mutant variants of the lease statements are derived by text replacement: a replacement that no longer matches
 * would leave the variant equal to the real statement and its {@code must_fail_*} test passing for the wrong reason
 */
class LeaseStatementVariantsTest {
    @Test
    void the_renewal_without_fence_drops_both_the_fence_and_the_owner_check() {
        assertThat(EventLogStorage.LEASE_RENEW_WITHOUT_FENCE)
                .doesNotContain("p.lease_fence = k.fence")
                .doesNotContain("p.lease_owner = k.owner")
                .contains("k.fence IS NOT NULL")
                .contains("k.owner IS NOT NULL");
        assertThat(EventLogStorage.LEASE_RENEW).contains("p.lease_fence = k.fence").contains("p.lease_owner = k.owner");
    }

    @Test
    void the_claim_ignoring_lease_drops_the_lease_condition_only() {
        assertThat(EventLogStorage.LEASE_CLAIM).contains("(p.lease_owner IS NULL OR p.lease_until < k.claim_time)");
        assertThat(EventLogStorage.LEASE_CLAIM_IGNORING_LEASE)
                .doesNotContain("p.lease_until < k.claim_time")
                .contains("p.exclusive = k.exclusive");
    }

    @Test
    void both_variants_keep_the_parameter_count_of_their_statement() {
        assertThat(EventLogStorage.LEASE_RENEW_WITHOUT_FENCE.chars().filter(c -> c == '?').count()).isEqualTo(EventLogStorage.LEASE_RENEW.chars().filter(c -> c == '?').count());
        assertThat(EventLogStorage.LEASE_CLAIM_IGNORING_LEASE.chars().filter(c -> c == '?').count()).isEqualTo(EventLogStorage.LEASE_CLAIM.chars().filter(c -> c == '?').count());
    }
}
