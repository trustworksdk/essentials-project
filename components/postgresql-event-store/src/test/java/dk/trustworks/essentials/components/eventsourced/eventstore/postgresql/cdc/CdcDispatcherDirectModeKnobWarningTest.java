/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDispatcherProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.DispatchedRowPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against the silent-drop footgun: when {@code deliveryMode=DIRECT}, the dispatcher is
 * not started, so any non-default {@code cdcDispatcher.*} setting has no effect. The dispatcher
 * surfaces this as a startup warning — this test verifies the detection logic itself.
 */
class CdcDispatcherDirectModeKnobWarningTest {

    @Test
    void inbox_mode_never_reports_ignored_knobs_even_with_non_default_values() {
        var props = CdcDispatcherProperties.defaults();
        props.setBatchSize(42);
        props.setPollInterval(Duration.ofMillis(999));
        props.setPoisonPolicy(PoisonPolicy.STOP);
        props.setDispatchedRowPolicy(DispatchedRowPolicy.DELETE);

        assertThat(CdcDispatcher.ignoredDispatcherKnobsForMode(props, CdcDeliveryMode.INBOX)).isEmpty();
    }

    @Test
    void direct_mode_with_default_props_reports_nothing() {
        assertThat(CdcDispatcher.ignoredDispatcherKnobsForMode(
                CdcDispatcherProperties.defaults(), CdcDeliveryMode.DIRECT))
                .isEmpty();
    }

    @Test
    void direct_mode_reports_each_non_default_knob() {
        var props = CdcDispatcherProperties.defaults();
        props.setBatchSize(42);
        props.setPollInterval(Duration.ofMillis(999));
        props.setPoisonPolicy(PoisonPolicy.STOP);
        props.setDispatchedRowPolicy(DispatchedRowPolicy.DELETE);

        var ignored = CdcDispatcher.ignoredDispatcherKnobsForMode(props, CdcDeliveryMode.DIRECT);

        assertThat(ignored)
                .hasSize(4)
                .anyMatch(s -> s.startsWith("pollInterval="))
                .anyMatch(s -> s.startsWith("batchSize="))
                .anyMatch(s -> s.startsWith("poisonPolicy="))
                .anyMatch(s -> s.startsWith("dispatchedRowPolicy="));
    }

    @Test
    void direct_mode_reports_only_the_knobs_that_actually_differ() {
        var props = CdcDispatcherProperties.defaults();
        props.setBatchSize(42);
        // other knobs untouched

        var ignored = CdcDispatcher.ignoredDispatcherKnobsForMode(props, CdcDeliveryMode.DIRECT);

        assertThat(ignored)
                .hasSize(1)
                .first().asString().startsWith("batchSize=42");
    }
}
