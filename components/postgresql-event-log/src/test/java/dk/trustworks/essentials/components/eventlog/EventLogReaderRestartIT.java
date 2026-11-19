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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4: a reader restart detaches every attached subscription. Two subscriptions are attached; a writer
 * holds a value below the head; the reader's backend is terminated, so the new reader starts at the head and has
 * forgotten the hole. The held value commits after the restart and must reach both, through their catch-up. Must-fail
 * variants: {@link WatermarkMutant#KEEP_ATTACHED_ON_READER_RESTART} and
 * {@link WatermarkMutant#RESTARTED_READER_AT_HEAD} (both on the type's first subscription, whose seams the shared
 * reader uses).
 */
class EventLogReaderRestartIT extends AbstractSubscriptionIT {
    @Test
    void a_reader_restart_detaches_every_attached_subscription_and_the_held_value_reaches_both() throws Exception {
        assertThat(heldValueDeliveredAfterARestart()).isTrue();
        assertThat(reads.detachReasons).filteredOn(Inbox.CloseReason.READER_RESTART.name()::equals).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WatermarkMutant.class, names = {"KEEP_ATTACHED_ON_READER_RESTART", "RESTARTED_READER_AT_HEAD"})
    void must_fail_the_held_value_is_lost(WatermarkMutant mutant) throws Exception {
        assertThat(heldValueDeliveredAfterARestart(mutant)).isFalse();
    }

    /**
     * @return whether both subscriptions delivered the held value; decided once both delivered an event appended after
     * it committed, which they receive in order behind it
     */
    private boolean heldValueDeliveredAfterARestart(WatermarkMutant... mutants) throws Exception {
        var first  = new Recorder();
        var second = new Recorder();
        var s1     = subscribe("first", first, mutants);
        var s2     = subscribe("second", second);
        var pre    = commitAppend("pre", 1);
        awaitDelivered(first, pre);
        awaitDelivered(second, pre);
        var held  = openAppend("held", 1);
        var above = commitAppend("above", 2);
        awaitDelivered(first, above, above + 1);
        awaitDelivered(second, above, above + 1);
        awaitTrue(() -> s1.phase() == Subscription.Phase.ATTACHED && s2.phase() == Subscription.Phase.ATTACHED);
        var before = eventLog.reader(TYPE);
        terminateReaderBackend();
        awaitTrue(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE) != before);
        awaitReads(3);
        held.commit();
        var tail = commitAppend("tail", 1);
        awaitDelivered(first, tail);
        awaitDelivered(second, tail);
        return first.deliveredOrder(held.globalOrder()) && second.deliveredOrder(held.globalOrder());
    }
}
