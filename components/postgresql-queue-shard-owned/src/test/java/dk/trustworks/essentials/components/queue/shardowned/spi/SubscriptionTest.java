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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * What {@link Subscription}'s {@code default} methods call. {@link Subscription#close()} is what try-with-resources, and
 * Spring's inferred destroy method for an {@link AutoCloseable} bean, call when the caller lets go of the subscription for
 * good, so it ends it with {@link Subscription#unsubscribe()}. It used to only {@link Subscription#stop()} it, which leaves
 * a {@code PostgresqlMessageQueue} subscription registered with its queue: the queue's {@code start()} started it again,
 * and the next {@code consume()} was counted as competing with it. {@code ShardOwnedUnsubscribeIT} pins both on the real
 * queue.
 */
class SubscriptionTest {

    @Test
    void close_ends_the_subscription_with_unsubscribe() {
        var subscription = new RecordingSubscription();

        subscription.close();

        assertThat(subscription.calls).as("close() is unsubscribe(), not a plain stop()").containsExactly("unsubscribe");
    }

    @Test
    void close_inside_try_with_resources_ends_the_subscription_with_unsubscribe() {
        var subscription = new RecordingSubscription();

        try (var closing = subscription) {
            assertThat(closing.calls).isEmpty();
        }

        assertThat(subscription.calls).containsExactly("unsubscribe");
    }

    @Test
    void an_implementation_keeping_no_registry_is_stopped_by_close_through_the_default_unsubscribe() {
        // unsubscribe() not overridden: the interface's default, stop() alone, for an implementation that keeps no registry
        var subscription = new KeepingNoRegistry();

        subscription.close();

        assertThat(subscription.calls).containsExactly("stop");
    }

    private static class RecordingSubscription extends KeepingNoRegistry {
        @Override
        public void unsubscribe() {
            calls.add("unsubscribe");
        }
    }

    private static class KeepingNoRegistry implements Subscription {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public boolean isStarted() {
            return false;
        }

        @Override
        public int shardsHeld() {
            return 0;
        }

        @Override
        public int unorderedShardsHeld() {
            return 0;
        }

        @Override
        public int orderedUnitsHeld() {
            return 0;
        }
    }
}
