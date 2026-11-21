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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ViewProjection#rebuild(ViewProjection.Truncate)} against a subscription whose delivery thread outlives its
 * stop (a handler that ignores interrupts): the view must not be cleared under it.
 */
class ViewProjectionRebuildTest {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** A handle whose stop() returns while the subscription keeps running */
    private SubscriptionHandle stillRunningAfterStop() {
        return (SubscriptionHandle) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SubscriptionHandle.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isRunning" -> true;
            case "stop" -> {
                calls.add("stop");
                yield null;
            }
            default -> null;
        });
    }

    private PageTransactionSource recordingSource() {
        var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> null);
        return () -> (PageTransactionSource.OpenPage) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PageTransactionSource.OpenPage.class},
                                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                                 case "connection" -> connection;
                                                                                 case "rollbackOnly" -> false;
                                                                                 default -> {
                                                                                     calls.add(method.getName());
                                                                                     yield null;
                                                                                 }
                                                                             });
    }

    @Test
    void a_rebuild_whose_subscription_did_not_stop_refuses_instead_of_clearing_the_view_under_it() throws Exception {
        // rebuildStopped used to truncate right after the bounded stop, while the delivery thread could still apply pages
        var eventLog = EventLog.builder().setDataSource(new org.postgresql.ds.PGSimpleDataSource()).build();
        var projection = ViewProjection.builder("orders-view", "Orders")
                                       .on(Added.class, (event, context) -> { })
                                       .transactions(recordingSource())
                                       .seams(new ProcessorSeams(Set.of(), (log, definition, handler, source) -> stillRunningAfterStop()))
                                       .build(eventLog, EssentialsJSONEventSerializers.create());
        projection.start();

        assertThatThrownBy(() -> projection.rebuild(connection -> calls.add("truncate")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not stop");
        assertThat(calls).containsExactly("stop");
    }
}
