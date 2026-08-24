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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The two contracts that make {@link DurableQueueMessageObserver} safe to call from the delivery threads: a
 * throwing observer cannot propagate, and one throwing observer cannot silence the others.
 */
class DurableQueueMessageObserverTest {

    @Test
    void safe_swallows_every_exception_from_every_callback() {
        var observer = DurableQueueMessageObserver.safe(new ThrowingObserver());

        // Recording must never be able to break delivery - so none of these may throw.
        assertThatNoException().isThrownBy(() -> observer.messageHandled(null, Duration.ZERO));
        assertThatNoException().isThrownBy(() -> observer.messageRedeliveryRequested(null));
        assertThatNoException().isThrownBy(() -> observer.messageRetried(null, new RuntimeException("cause"), Duration.ZERO));
        assertThatNoException().isThrownBy(() -> observer.messageDeadLettered(null, new RuntimeException("cause")));
    }

    /**
     * A composite must guard each observer individually. Guarding only the composite would let the first throwing
     * observer stop every later one from being notified — so statistics would silently stop the moment an
     * unrelated observer broke.
     */
    @Test
    void a_throwing_observer_does_not_stop_the_others_in_a_composite() {
        var recorded = new CopyOnWriteArrayList<String>();
        var observer = DurableQueueMessageObserver.composite(List.of(new RecordingObserver(recorded, "first"),
                                                                    new ThrowingObserver(),
                                                                    new RecordingObserver(recorded, "third")));

        assertThatNoException().isThrownBy(() -> observer.messageHandled(null, Duration.ZERO));

        assertThat(recorded).containsExactly("first", "third");
    }

    @Test
    void composite_of_nothing_is_none_and_composite_of_one_is_that_one() {
        assertThat(DurableQueueMessageObserver.composite(List.of())).hasToString("DurableQueueMessageObserver.none()");

        var single = new RecordingObserver(new CopyOnWriteArrayList<>(), "only");
        assertThat(DurableQueueMessageObserver.composite(List.of(single))).hasToString("safe(" + single + ")");
    }

    /**
     * {@code safe} is idempotent, so plumbing that wraps defensively at more than one layer does not stack
     * decorators — which would multiply the "logged once" guard into once per layer.
     */
    @Test
    void safe_does_not_wrap_an_already_safe_observer() {
        var once  = DurableQueueMessageObserver.safe(new ThrowingObserver());
        var twice = DurableQueueMessageObserver.safe(once);

        assertThat(twice).isSameAs(once);
    }

    private static final class ThrowingObserver implements DurableQueueMessageObserver {
        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            throw new RuntimeException("thrown on purpose");
        }

        @Override
        public void messageRedeliveryRequested(QueuedMessage message) {
            throw new RuntimeException("thrown on purpose");
        }

        @Override
        public void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
            throw new RuntimeException("thrown on purpose");
        }

        @Override
        public void messageDeadLettered(QueuedMessage message, Throwable cause) {
            throw new RuntimeException("thrown on purpose");
        }
    }

    private record RecordingObserver(List<String> recorded, String name) implements DurableQueueMessageObserver {
        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            recorded.add(name);
        }
    }
}
