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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * {@code PostgresqlMessageQueue}'s observer fan-out for {@link QueueObserver#takeoverStalled}: every observer is told,
 * whatever an earlier one throws, and the first throw is handed back to the engine (which logs it) once all have been
 * told. A non-critical {@code Error} counts as such a throw: caught only {@code RuntimeException}, an {@code AssertionError}
 * from one observer stopped the observers after it hearing the transition, so their stall never started or never ended.
 * No database: the fan-out asks the {@code DataSource} for nothing.
 */
class TakeoverStalledFanOutTest {

    @Test
    void an_observer_throwing_a_non_critical_error_does_not_keep_the_observers_after_it_from_hearing_the_transition() {
        var queue    = new PostgresqlMessageQueue(untouchable(), (short) 1, 4, "fan-out");
        var told     = new CopyOnWriteArrayList<String>();
        var assertion = new AssertionError("observer test double");
        var runtime  = new IllegalStateException("observer bug");
        queue.addObserver(throwing(assertion));
        queue.addObserver(recording(told));
        queue.addObserver(throwing(runtime));
        queue.addObserver(recording(told));

        assertThatThrownBy(() -> queue.fanOut.takeoverStalled(MessageId.Lane.ORDERED, 7, true))
                .as("the first throw is rethrown, for the engine to log")
                .isSameAs(assertion)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(runtime));
        assertThat(told).as("every observer after the throwing ones still heard it").containsExactly("ORDERED/7=true", "ORDERED/7=true");
    }

    @Test
    void a_runtime_exception_thrown_first_carries_a_later_non_critical_error_as_suppressed() {
        var queue     = new PostgresqlMessageQueue(untouchable(), (short) 1, 4, "fan-out");
        var told      = new CopyOnWriteArrayList<String>();
        var runtime   = new IllegalStateException("observer bug");
        var assertion = new AssertionError("observer test double");
        queue.addObserver(throwing(runtime));
        queue.addObserver(throwing(assertion));
        queue.addObserver(recording(told));

        assertThatThrownBy(() -> queue.fanOut.takeoverStalled(MessageId.Lane.UNORDERED, 2, false))
                .isSameAs(runtime)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(assertion));
        assertThat(told).containsExactly("UNORDERED/2=false");
    }

    @Test
    void observers_throwing_one_shared_instance_do_not_keep_the_observers_after_them_from_hearing_the_transition() {
        var queue  = new PostgresqlMessageQueue(untouchable(), (short) 1, 4, "fan-out");
        var told   = new CopyOnWriteArrayList<String>();
        var shared = new IllegalStateException("one instance thrown by every failing observer");
        queue.addObserver(throwing(shared));
        queue.addObserver(throwing(shared));
        queue.addObserver(recording(told));

        assertThatThrownBy(() -> queue.fanOut.takeoverStalled(MessageId.Lane.ORDERED, 3, true))
                .as("the shared instance is rethrown, not suppressed in itself (addSuppressed refuses that)")
                .isSameAs(shared)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
        assertThat(told).as("the observer after them still heard it").containsExactly("ORDERED/3=true");
    }

    @Test
    void a_critical_error_still_propagates_at_once() {
        var queue    = new PostgresqlMessageQueue(untouchable(), (short) 1, 4, "fan-out");
        var critical = new LinkageError("critical");
        queue.addObserver(throwing(critical));

        assertThatThrownBy(() -> queue.fanOut.takeoverStalled(MessageId.Lane.UNORDERED, 0, true)).isSameAs(critical);
    }

    private static QueueObserver throwing(Throwable throwable) {
        return new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                switch (throwable) {
                    case RuntimeException e -> throw e;
                    case Error e -> throw e;
                    default -> throw new IllegalArgumentException(throwable);
                }
            }
        };
    }

    private static QueueObserver recording(List<String> told) {
        return new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                told.add(lane + "/" + shard + "=" + stalled);
            }
        };
    }

    /**
     * A {@code DataSource} that refuses everything: the fan-out must not need one.
     */
    private static DataSource untouchable() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "untouchable";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
