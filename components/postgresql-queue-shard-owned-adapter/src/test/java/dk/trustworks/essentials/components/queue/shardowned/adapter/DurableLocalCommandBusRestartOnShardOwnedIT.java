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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.reactive.command.CommandHandler;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.*;

/**
 * A {@link DurableLocalCommandBus} on {@link ShardOwnedDurableQueues}, stopped and started twice, and the
 * {@code DurableQueues} stopped and started under it, against a real {@link PostgresqlMessageQueue}: the queue keeps one
 * subscription of the bus, under the instance id it was built with.
 * <p>
 * Every stop of the bus cancels its consumer, and every start consumes from the command queue anew. The consumer used
 * to only stop its engine subscription, which stays registered with the queue: each restart's {@code consume()} then
 * logged a WARN calling the bus a COMPETING consumer, registered under the next instance id suffix, and a {@code stop()}
 * and {@code start()} of the engine queue started every abandoned subscription again - one more live instance per
 * restart, each taking its share of the units. It now unsubscribes.
 */
@Testcontainers(disabledWithoutDocker = true)
class DurableLocalCommandBusRestartOnShardOwnedIT {

    private static final QueueName COMMAND_QUEUE = QueueName.of("commands");
    private static final String    INSTANCE_ID   = "bus-restart";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    private HikariDataSource              dataSource;
    private TestMessageQueues             queues;
    private ShardOwnedDurableQueues       durableQueues;
    private DurableLocalCommandBus        commandBus;
    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger engineLogger;
    private final List<Object>            handled = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, engineName(COMMAND_QUEUE), 2);

        logged = new ListAppender<>();
        logged.start();
        engineLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        engineLogger.addAppender(logged);

        queues = new TestMessageQueues(dataSource);
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(Jdbi.create(dataSource)))
                                               .setDataSource(dataSource)
                                               .build();
        durableQueues.start();
        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(durableQueues)
                                           .setCommandQueueName(COMMAND_QUEUE)
                                           .setParallelSendAndDontWaitConsumers(1)
                                           .build();
        commandBus.addCommandHandler(new CommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                return PlaceOrder.class.equals(commandType);
            }

            @Override
            public Object handle(Object command) {
                handled.add(command);
                return null;
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (commandBus != null) {
            commandBus.stop();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (queues != null) {
            queues.close();
        }
        if (engineLogger != null) {
            engineLogger.detachAppender(logged);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void restarting_the_command_bus_and_its_durable_queues_keeps_one_subscription_under_the_same_instance_id() throws Exception {
        commandBus.start();
        assertThat(registeredInstances()).as("premise: the bus subscribed under the queue's instance id").containsExactly(INSTANCE_ID);

        for (var restart = 0; restart < 2; restart++) {
            commandBus.stop();
            commandBus.start();
            assertThat(registeredInstances()).as("restart " + (restart + 1) + " subscribed under the same instance id, and alone")
                                             .containsExactly(INSTANCE_ID);
        }
        durableQueues.stop();
        durableQueues.start();
        assertThat(registeredInstances()).as("a stop and start of the DurableQueues subscribed under the same instance id, and alone")
                                         .containsExactly(INSTANCE_ID);

        assertThat(competingWarnings()).as("no restart was reported as a competing consumer").isEmpty();

        // An engine queue start() after its stop() starts every subscription still registered with it.
        var engineQueue = engineQueue();
        engineQueue.stop();
        engineQueue.start();
        assertThat(registeredInstances()).as("the engine queue's restart started the bus's live subscription alone")
                                         .containsExactly(INSTANCE_ID);

        commandBus.sendAndDontWait(new PlaceOrder("order-1"));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(handled).as("the restarted bus handles its commands").hasSize(1));
    }

    private PostgresqlMessageQueue engineQueue() throws SQLException {
        return (PostgresqlMessageQueue) queues.findQueue(engineName(COMMAND_QUEUE)).orElseThrow();
    }

    private List<String> registeredInstances() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT instance_id FROM " + ShardOwnedSchema.INSTANCE_TABLE
                                                                 + " ORDER BY instance_id");
             var resultSet = statement.executeQuery()) {
            var instances = new ArrayList<String>();
            while (resultSet.next()) {
                instances.add(resultSet.getString(1));
            }
            return instances;
        }
    }

    private List<String> competingWarnings() {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == WARN)
                          .map(ILoggingEvent::getFormattedMessage)
                          .filter(message -> message.contains("COMPETING"))
                          .toList();
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    record PlaceOrder(String orderId) {
    }

    private static final class TestMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName, PostgresqlMessageQueue> built = new LinkedHashMap<>();

        TestMessageQueues(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public synchronized List<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName> queueNames() throws SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public synchronized Optional<dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue> findQueue(
                dk.trustworks.essentials.components.queue.shardowned.spi.QueueName queueName) throws SQLException {
            var registered = ShardOwnedSchema.resolve(dataSource, queueName);
            if (registered.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(built.computeIfAbsent(queueName,
                                                     name -> PostgresqlMessageQueue.builder()
                                                                                   .setDataSource(dataSource)
                                                                                   .setQueueName(name)
                                                                                   .setInstanceId(INSTANCE_ID)
                                                                                   .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(PostgresqlMessageQueue::close);
        }
    }
}
