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

package dk.trustworks.essentials.components.foundation.causation;

import dk.trustworks.essentials.components.foundation.types.EventId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class CausationContextTest {
    private static final EventId CAUSE       = EventId.of("cause");
    private static final EventId OTHER_CAUSE = EventId.of("other-cause");

    @Test
    void nothing_is_bound_outside_a_binding() {
        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void the_cause_is_visible_inside_run_and_gone_after() {
        var seen = new AtomicReference<Optional<EventId>>();

        CausationContext.where(CAUSE).run(() -> seen.set(CausationContext.current()));

        assertThat(seen.get()).contains(CAUSE);
        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void call_returns_the_operations_result_with_the_cause_bound() {
        var result = CausationContext.where(CAUSE).call(CausationContext::current);

        assertThat(result).contains(CAUSE);
        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void call_propagates_a_checked_exception_unchanged() {
        var failure = new IOException("boom");

        assertThatThrownBy(() -> CausationContext.where(CAUSE).call(() -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void run_ends_the_binding_when_the_action_throws() {
        assertThatThrownBy(() -> CausationContext.where(CAUSE).run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void the_innermost_binding_wins_and_the_outer_one_is_restored_after_it() {
        var inner      = new AtomicReference<Optional<EventId>>();
        var outerAfter = new AtomicReference<Optional<EventId>>();

        CausationContext.where(CAUSE).run(() -> {
            CausationContext.where(OTHER_CAUSE).run(() -> inner.set(CausationContext.current()));
            outerAfter.set(CausationContext.current());
        });

        assertThat(inner.get()).contains(OTHER_CAUSE);
        assertThat(outerAfter.get()).contains(CAUSE);
    }

    @Test
    void binding_an_optional_cause_binds_it() {
        var seen = CausationContext.where(Optional.of(CAUSE)).call(CausationContext::current);

        assertThat(seen).contains(CAUSE);
    }

    @Test
    void binding_no_cause_hides_an_outer_binding() {
        // A cause captured as "none" and re-bound later must not inherit an unrelated outer cause
        var seen = CausationContext.where(CAUSE)
                                   .call(() -> CausationContext.where(Optional.empty()).call(CausationContext::current));

        assertThat(seen).isEmpty();
    }

    @Test
    void a_binding_does_not_reach_a_task_handed_to_another_thread() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Optional<EventId>> seenByTask = CausationContext.where(CAUSE)
                                                                   .call(() -> executor.submit(CausationContext::current));

            assertThat(seenByTask.get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void a_captured_cause_can_be_rebound_on_another_thread() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Optional<EventId>> seenByTask = CausationContext.where(CAUSE).call(() -> {
                var captured = CausationContext.current();
                return executor.submit(() -> CausationContext.where(captured).call(CausationContext::current));
            });

            assertThat(seenByTask.get(5, TimeUnit.SECONDS)).contains(CAUSE);
        }
    }

    @Test
    void a_binding_does_not_leak_into_the_pooled_threads_next_task() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> CausationContext.where(CAUSE).run(() -> { })).get(5, TimeUnit.SECONDS);

            assertThat(executor.submit(CausationContext::current).get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void null_arguments_are_rejected() {
        assertThatThrownBy(() -> CausationContext.where((EventId) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CausationContext.where((Optional<EventId>) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CausationContext.where(CAUSE).run(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
