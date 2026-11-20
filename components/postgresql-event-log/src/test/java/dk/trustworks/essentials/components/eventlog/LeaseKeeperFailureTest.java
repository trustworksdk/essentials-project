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

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The lease keeper must outlive a pass that ends in an {@link Error}: a dead keeper renews and claims nothing, so every
 * exclusive subscription of the node would suspend until some other subscription registered. No database: the
 * {@link DataSource} throws an {@link AssertionError} on every connection.
 */
class LeaseKeeperFailureTest {
    @Test
    void the_keeper_keeps_passing_after_a_pass_that_throws_an_error() {
        var passes = new AtomicInteger();
        var dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) {
                passes.incrementAndGet();
                throw new AssertionError("an Error inside a pass");
            }
            throw new UnsupportedOperationException(method.getName());
        });
        var settings = EventLogSettings.defaults().withLease(Duration.ofSeconds(2), Duration.ofMillis(20), Duration.ofSeconds(1));
        var keeper   = new LeaseKeeper(dataSource, settings, EventLogObserver.NONE, "node/abcd1234", () -> TestSeams.NONE);
        try {
            keeper.register(new Lease(LeaseTest.KEY, new Lease.NewRow("FRESH", "PERIODIC", 1), "node/abcd1234", Duration.ofSeconds(1).toNanos(), System::nanoTime));

            // Before, the first Error ended the keeper's thread: one pass, never a second
            await().atMost(Duration.ofSeconds(10)).until(() -> passes.get() >= 3);
            assertThat(keeper.isRunning()).isTrue();
        } finally {
            keeper.stop();
        }
    }
}
