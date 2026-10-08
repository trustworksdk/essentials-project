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

package dk.trustworks.essentials.components.foundation.postgresql;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.reactive.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N-14: {@code LLM/LLM-foundation.md} documented a {@code MultiTableChangeListener.builder()} that did not exist, so its
 * snippet did not compile. {@link #the_documented_snippet_compiles_and_delivers_notifications()} holds that snippet
 * verbatim - if the documentation changes, change it here too.
 */
@Testcontainers
class MultiTableChangeListenerBuilderIT {
    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("listen-notify-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                    jdbi;
    private JSONSerializer                          jsonSerializer;
    private EventBus                                eventBus;
    private List<Object>                            received;
    private MultiTableChangeListener<?>             createdListener;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS orders");
            handle.execute("CREATE TABLE orders (order_id TEXT PRIMARY KEY, status TEXT)");
            ListenNotify.addChangeNotificationTriggerToTable(handle, "orders", List.of(ListenNotify.SqlOperation.INSERT), "order_id", "status");
        });
        jsonSerializer = new Jackson3JSONSerializer(EssentialsObjectMappers.createJackson3ObjectMapper());
        received = new CopyOnWriteArrayList<>();
        var localEventBus = new LocalEventBus("Notifications", 1, (consumer, event, e) -> {
            throw new RuntimeException(e);
        });
        localEventBus.addSyncSubscriber(received::add);
        eventBus = localEventBus;
    }

    @AfterEach
    void cleanup() {
        if (createdListener != null) {
            createdListener.stop();
            createdListener.close();
        }
    }

    @Test
    void the_documented_snippet_compiles_and_delivers_notifications() {
        // -- Verbatim from LLM/LLM-foundation.md § MultiTableChangeListener --
        MultiTableChangeListener<TableChangeNotification> listener = MultiTableChangeListener.builder()
            .setJdbi(jdbi)
            .setPollingInterval(Duration.ofMillis(100))
            .setJsonSerializer(jsonSerializer)
            .setEventBus(eventBus)  // Publishes notifications here
            .setFilterDuplicateNotifications(true)
            .build();

        listener.listenToNotificationsFor("orders", OrderNotification.class);
        listener.start();
        // -- End of snippet --
        createdListener = listener;

        jdbi.useHandle(handle -> handle.execute("INSERT INTO orders (order_id, status) VALUES ('order-1', 'NEW')"));

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(received).singleElement()
                                                           .isInstanceOfSatisfying(OrderNotification.class, notification -> {
                                                               assertThat(notification.orderId).isEqualTo("order-1");
                                                               assertThat(notification.status).isEqualTo("NEW");
                                                               assertThat(notification.getTableName()).isEqualTo("orders");
                                                               assertThat(notification.getOperation()).isEqualTo(ListenNotify.SqlOperation.INSERT);
                                                           }));
    }

    @Test
    void a_dedicated_listener_data_source_is_used_for_the_listen_connection() {
        var listenerDataSource = new PGSimpleDataSource();
        listenerDataSource.setURL(postgreSQLContainer.getJdbcUrl());
        listenerDataSource.setUser(postgreSQLContainer.getUsername());
        listenerDataSource.setPassword(postgreSQLContainer.getPassword());
        listenerDataSource.setApplicationName("dedicated-listener");

        MultiTableChangeListener<TableChangeNotification> listener = MultiTableChangeListener.builder()
                                                                                             .setJdbi(jdbi)
                                                                                             .setJsonSerializer(jsonSerializer)
                                                                                             .setEventBus(eventBus)
                                                                                             .setListenerDataSource(listenerDataSource)
                                                                                             .setPollThreadName("orders-listener")
                                                                                             .build();
        createdListener = listener;
        listener.start();
        listener.listenToNotificationsFor("orders", OrderNotification.class);

        int dedicatedListenerSessions = jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'dedicated-listener'")
                                                                        .mapTo(Integer.class)
                                                                        .one());
        assertThat(dedicatedListenerSessions).isEqualTo(1);
        jdbi.useHandle(handle -> handle.execute("INSERT INTO orders (order_id, status) VALUES ('order-2', 'NEW')"));
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(received).singleElement().isInstanceOf(OrderNotification.class));
    }

    public static class OrderNotification extends TableChangeNotification {
        @JsonProperty("order_id")
        private String orderId;
        @JsonProperty("status")
        private String status;
    }
}
