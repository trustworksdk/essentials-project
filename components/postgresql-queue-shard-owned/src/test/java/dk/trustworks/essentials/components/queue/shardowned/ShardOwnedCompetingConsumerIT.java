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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second {@code consume()} on one queue, which is a competing consumer and not "the other lane".
 * <p>
 * One {@code consume()} serves both lanes, and the lane reaches the handler as the nullable ordering
 * key. A caller who registers one consumer per lane instead gets a second instance identity, a share
 * of the units per subscription, and each handler receiving the messages the caller meant for the
 * other one — all of it silent, because nothing about it is an error. It is legal: a genuine
 * competing consumer is a supported thing to want and the engine cannot tell the two apart.
 * <p>
 * So what is asserted here is the one signal that separates them — a WARN naming the additional
 * instance — and, either way, that nothing is stranded by the split.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedCompetingConsumerIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;
    private static final int   MESSAGES    = 40;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource         dataSource;
    private ListAppender<ILoggingEvent> logged;
    private ch.qos.logback.classic.Logger queueLogger;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        logged = new ListAppender<>();
        logged.start();
        queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        queueLogger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        if (queueLogger != null) {
            queueLogger.detachAppender(logged);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void a_second_consume_warns_that_it_is_a_competing_consumer_and_strands_nothing() throws Exception {
        var deliveredToFirst  = ConcurrentHashMap.<String>newKeySet();
        var deliveredToSecond = ConcurrentHashMap.<String>newKeySet();

        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "competing")) {
            queue.consume((messageId, key, payload, payloadType) -> deliveredToFirst.add(body(payload)),
                          ConsumerOptions.defaults());

            // The first subscription is the ordinary case and must stay quiet — a warning everyone
            // sees is a warning nobody reads.
            assertThat(warnings()).isEmpty();

            queue.consume((messageId, key, payload, payloadType) -> deliveredToSecond.add(body(payload)),
                          ConsumerOptions.defaults());

            assertThat(warnings()).hasSize(1);
            assertThat(warnings().get(0)).contains("COMPETING")
                                         .contains("competing-1")   // the additional instance identity
                                         .contains("BOTH lanes");   // why a caller reaches for this by mistake

            var batch = new ArrayList<Message>();
            for (var index = 0; index < MESSAGES; index++) {
                batch.add(Message.of(("u-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueue(batch);

            // Both identities are real consumers, so the units they split between them are all served.
            // Which handler gets a given message is not a property worth asserting — that is exactly
            // what the caller loses by registering twice.
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> {
                          var everything = new HashSet<>(deliveredToFirst);
                          everything.addAll(deliveredToSecond);
                          assertThat(everything).hasSize(MESSAGES);
                      });
        }
    }

    private static String body(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private List<String> warnings() {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == WARN)
                          .map(ILoggingEvent::getFormattedMessage)
                          .toList();
    }
}
