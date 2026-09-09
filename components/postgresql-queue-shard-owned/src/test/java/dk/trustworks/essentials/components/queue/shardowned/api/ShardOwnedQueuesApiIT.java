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

package dk.trustworks.essentials.components.queue.shardowned.api;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.components.queue.shardowned.LabPostgres;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The administrative contract, against a real database.
 *
 * <h2>What is actually at stake here</h2>
 * The engine's own {@code MessageQueue} by-id operations are covered by {@code ShardOwnedAdminSurfaceIT}.
 * This class covers the three things the API layer adds on top, each of which is a way to get it
 * wrong that the engine tests cannot see:
 * <ul>
 *     <li><b>Authorisation.</b> Every operation must refuse a principal without the role, and the
 *         read and write roles must be genuinely different — an API where {@code QUEUE_READER}
 *         happens to satisfy {@code purgeQueue} would pass every functional test in the suite.</li>
 *     <li><b>Payload redaction.</b> A reader without the payload role must still get the message.
 *         Withholding the whole record instead would be safe and useless; returning the payload
 *         anyway would be the leak the role exists to prevent.</li>
 *     <li><b>Name resolution.</b> {@link MessageId} is unique within a queue only, so an id looked up
 *         against the wrong queue must not resolve. That is the failure mode that makes the queue
 *         name mandatory on every operation, and it is silent if it is wrong.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedQueuesApiIT {

    private static final QueueName ORDERS   = QueueName.of("api-orders");
    private static final QueueName PAYMENTS = QueueName.of("api-payments");

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource     dataSource;
    private TestMessageQueues    queues;
    private ShardOwnedQueuesApi  api;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, ORDERS, 4);
        ShardOwnedSchema.registerQueue(dataSource, PAYMENTS, 4);

        queues = new TestMessageQueues(dataSource);
        api = new DefaultShardOwnedQueuesApi(new RoleSecurityProvider(), queues);
    }

    @AfterEach
    void tearDown() {
        queues.close();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------- reading

    @Test
    void the_registry_is_what_gets_listed_not_what_this_process_has_built() {
        assertThat(api.getQueueNames(reader()))
                .describedAs("both registered queues, though nothing has built either of them yet")
                .containsExactlyInAnyOrder(ORDERS, PAYMENTS);
    }

    @Test
    void status_reports_depth_and_ownership_together() throws Exception {
        queues.queue(ORDERS).enqueue(List.of(Message.of("a".getBytes(StandardCharsets.UTF_8), 1),
                                             Message.ordered("b".getBytes(StandardCharsets.UTF_8), 1, "k", 0L)));

        var status = api.getQueueStatus(reader(), ORDERS).orElseThrow();

        assertThat(status.queueName()).isEqualTo(ORDERS);
        assertThat(status.shardCount()).isEqualTo(4);
        assertThat(status.unorderedDepth()).isEqualTo(1);
        assertThat(status.orderedDepth()).isEqualTo(1);
        assertThat(status.unownedShards())
                .describedAs("nothing is consuming, so every unit of both lanes is unowned - which is "
                             + "the state depth alone cannot express and this endpoint exists to expose. "
                             + "The lanes have different totals: 4 unordered shards, and the ordered "
                             + "lane's fixed unit space")
                .isEqualTo(4 + ShardOwnedSchema.ORDERED_UNITS);
        assertThat(status.fullyOwned()).isFalse();
        assertThat(status.maxInstances())
                .describedAs("the larger of the two lanes' ceilings caps horizontal scale")
                .isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
    }

    @Test
    void an_unregistered_queue_is_absent_rather_than_an_error() {
        assertThat(api.getQueueStatus(reader(), QueueName.of("never-registered"))).isEmpty();
        assertThat(api.getMessage(reader(), QueueName.of("never-registered"),
                                  new MessageId(MessageId.Lane.UNORDERED, 0, 1L))).isEmpty();
        assertThat(api.getDeadLetterMessages(reader(), QueueName.of("never-registered"), 0, 10)).isEmpty();
        assertThat(api.deleteMessage(writer(), QueueName.of("never-registered"),
                                     new MessageId(MessageId.Lane.UNORDERED, 0, 1L))).isFalse();
        assertThat(api.purgeQueue(writer(), QueueName.of("never-registered"))).isZero();
    }

    // ------------------------------------------------------------ redaction

    @Test
    void a_reader_without_the_payload_role_gets_the_message_but_not_its_contents() throws Exception {
        var id = queues.queue(ORDERS).enqueue(Message.of("card number 4111".getBytes(StandardCharsets.UTF_8), 7));

        var redacted = api.getMessage(reader(), ORDERS, id).orElseThrow();
        assertThat(redacted.payload())
                .describedAs("withheld, and null rather than empty - an empty payload is a legal "
                             + "message and has to stay distinguishable from one being withheld")
                .isNull();
        assertThat(redacted.id()).isEqualTo(id.toString());
        assertThat(redacted.payloadType())
                .describedAs("everything except the contents is still there, or the endpoint is useless "
                             + "to the operator it is for")
                .isEqualTo(7);
        assertThat(redacted.attempts()).isZero();
        assertThat(redacted.enqueuedAt()).isNotNull();

        var full = api.getMessage(payloadReader(), ORDERS, id).orElseThrow();
        assertThat(full.payload()).isEqualTo("card number 4111");
    }

    @Test
    void a_payload_that_is_not_text_is_rendered_as_hex_rather_than_as_replacement_characters() throws Exception {
        var binary = new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1};
        var id     = queues.queue(ORDERS).enqueue(Message.of(binary, 1));

        assertThat(api.getMessage(payloadReader(), ORDERS, id).orElseThrow().payload())
                .describedAs("lossy UTF-8 decoding would render this as U+FFFD and look like text")
                .isEqualTo("\\xc328a0a1");
    }

    @Test
    void dead_letters_are_redacted_on_the_same_rule() throws Exception {
        var id = queues.queue(ORDERS).enqueue(Message.of("secret".getBytes(StandardCharsets.UTF_8), 1));
        queues.queue(ORDERS).markAsDeadLetter(id, "parked by hand");

        var redacted = api.getDeadLetterMessages(reader(), ORDERS, 0, 10);
        assertThat(redacted).singleElement().satisfies(message -> {
            assertThat(message.payload()).isNull();
            assertThat(message.isDeadLetter()).isTrue();
            assertThat(message.lastError()).isEqualTo("parked by hand");
        });

        assertThat(api.getDeadLetterMessages(payloadReader(), ORDERS, 0, 10))
                .singleElement()
                .satisfies(message -> assertThat(message.payload()).isEqualTo("secret"));
    }

    // ------------------------------------------------------- name resolution

    /**
     * Sequences are per {@code (queue, shard)}, so the first message of every queue has the same id.
     * If the API resolved by id alone — or resolved the name and then ignored it — an operator
     * deleting a stuck message in one queue would delete an unrelated message in another, and nothing
     * about the response would say so.
     */
    @Test
    void an_id_from_one_queue_does_not_address_a_message_in_another() throws Exception {
        var inOrders   = queues.queue(ORDERS).enqueue(Message.of("orders".getBytes(StandardCharsets.UTF_8), 1));
        var inPayments = queues.queue(PAYMENTS).enqueue(Message.of("payments".getBytes(StandardCharsets.UTF_8), 1));

        assertThat(inPayments)
                .describedAs("the premise: the two queues really did mint the same id")
                .isEqualTo(inOrders);

        assertThat(api.getMessage(payloadReader(), ORDERS, inOrders).orElseThrow().payload()).isEqualTo("orders");
        assertThat(api.getMessage(payloadReader(), PAYMENTS, inOrders).orElseThrow().payload()).isEqualTo("payments");

        assertThat(api.deleteMessage(writer(), ORDERS, inOrders)).isTrue();
        assertThat(api.getMessage(reader(), PAYMENTS, inPayments))
                .describedAs("deleting in one queue must leave the identically-numbered message in the other")
                .isPresent();
    }

    // ------------------------------------------------------------- writing

    @Test
    void the_write_operations_act_through_to_the_queue() throws Exception {
        var queue    = queues.queue(ORDERS);
        var retry    = queue.enqueue(Message.of("retry".getBytes(StandardCharsets.UTF_8), 1));
        var park     = queue.enqueue(Message.of("park".getBytes(StandardCharsets.UTF_8), 1));
        var remove   = queue.enqueue(Message.of("remove".getBytes(StandardCharsets.UTF_8), 1));

        assertThat(api.retryMessage(writer(), ORDERS, retry, Duration.ofHours(1))).isTrue();
        assertThat(api.getMessage(reader(), ORDERS, retry).orElseThrow().visibleAt())
                .isAfter(java.time.OffsetDateTime.now().plusMinutes(50));

        assertThat(api.markAsDeadLetterMessage(writer(), ORDERS, park, "operator parked it")).isTrue();
        assertThat(api.getMessage(reader(), ORDERS, park)).isEmpty();
        assertThat(api.getDeadLetterMessages(reader(), ORDERS, 0, 10)).hasSize(1);

        assertThat(api.resurrectDeadLetterMessage(writer(), ORDERS, park)).isTrue();
        assertThat(api.getDeadLetterMessages(reader(), ORDERS, 0, 10)).isEmpty();

        assertThat(api.deleteMessage(writer(), ORDERS, remove)).isTrue();
        assertThat(api.deleteMessage(writer(), ORDERS, remove))
                .describedAs("already gone reports false, it does not throw")
                .isFalse();

        assertThat(api.purgeQueue(writer(), ORDERS)).isPositive();
        var status = api.getQueueStatus(reader(), ORDERS).orElseThrow();
        assertThat(status.unorderedDepth()).isZero();
        assertThat(status.orderedDepth()).isZero();
        assertThat(status.deadLetteredDepth()).isZero();
    }

    @Test
    void a_dead_letter_page_is_capped_rather_than_served_at_whatever_was_asked_for() throws Exception {
        assertThat(DefaultShardOwnedQueuesApi.MAX_PAGE_SIZE).isEqualTo(1_000);
        assertThatThrownBy(() -> api.getDeadLetterMessages(reader(), ORDERS, 0, 0))
                .describedAs("a page of nothing is a caller mistake, not an empty result")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.getDeadLetterMessages(reader(), ORDERS, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> api.getDeadLetterMessages(reader(), ORDERS, 0, Integer.MAX_VALUE))
                .describedAs("an over-large ask is clamped, not refused - paging through a backlog "
                             + "should not require guessing the limit")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------- authorisation

    @Test
    void reads_require_the_reader_role() {
        var nobody = new TestPrincipal(Set.of());
        assertThatThrownBy(() -> api.getQueueNames(nobody)).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.getQueueStatus(nobody, ORDERS)).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.getMessage(nobody, ORDERS, new MessageId(MessageId.Lane.UNORDERED, 0, 1L)))
                .isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.getDeadLetterMessages(nobody, ORDERS, 0, 10))
                .isInstanceOf(EssentialsSecurityException.class);
    }

    /**
     * The distinction that would be invisible if it were wrong: a reader can read everything and
     * change nothing. An implementation that validated the reader role on the write paths would pass
     * every other test in this class.
     */
    @Test
    void a_reader_cannot_write() {
        var id = new MessageId(MessageId.Lane.UNORDERED, 0, 1L);
        assertThatThrownBy(() -> api.deleteMessage(reader(), ORDERS, id)).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.retryMessage(reader(), ORDERS, id, Duration.ZERO)).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.markAsDeadLetterMessage(reader(), ORDERS, id, "no")).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.resurrectDeadLetterMessage(reader(), ORDERS, id)).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.purgeQueue(reader(), ORDERS)).isInstanceOf(EssentialsSecurityException.class);
    }

    /**
     * The payload role grants contents and nothing else — holding it must not smuggle in write access,
     * and lacking the reader role must not be excused by holding it.
     */
    @Test
    void the_payload_role_is_not_a_write_role_and_not_a_read_role_on_its_own() {
        var payloadOnly = new TestPrincipal(Set.of(EssentialsSecurityRoles.QUEUE_PAYLOAD_READER.getRoleName()));
        assertThatThrownBy(() -> api.getQueueNames(payloadOnly)).isInstanceOf(EssentialsSecurityException.class);
        assertThatThrownBy(() -> api.purgeQueue(payloadOnly, ORDERS)).isInstanceOf(EssentialsSecurityException.class);
    }

    @Test
    void the_admin_role_satisfies_everything_including_payloads() throws Exception {
        var admin = new TestPrincipal(Set.of(EssentialsSecurityRoles.ESSENTIALS_ADMIN.getRoleName()));
        var id    = queues.queue(ORDERS).enqueue(Message.of("visible".getBytes(StandardCharsets.UTF_8), 1));

        assertThat(api.getQueueNames(admin)).contains(ORDERS);
        assertThat(api.getMessage(admin, ORDERS, id).orElseThrow().payload()).isEqualTo("visible");
        assertThat(api.deleteMessage(admin, ORDERS, id)).isTrue();
    }

    // ---------------------------------------------------------- test doubles

    private static Object reader() {
        return new TestPrincipal(Set.of(EssentialsSecurityRoles.QUEUE_READER.getRoleName()));
    }

    private static Object payloadReader() {
        return new TestPrincipal(Set.of(EssentialsSecurityRoles.QUEUE_READER.getRoleName(),
                                        EssentialsSecurityRoles.QUEUE_PAYLOAD_READER.getRoleName()));
    }

    private static Object writer() {
        return new TestPrincipal(Set.of(EssentialsSecurityRoles.QUEUE_WRITER.getRoleName()));
    }

    private record TestPrincipal(Set<String> roles) {
    }

    private static final class RoleSecurityProvider implements EssentialsSecurityProvider {
        @Override
        public boolean isAllowed(Object principal, String requiredRole) {
            return principal instanceof TestPrincipal test && test.roles().contains(requiredRole);
        }

        @Override
        public Optional<String> getPrincipalName(Object principal) {
            return Optional.of(String.valueOf(principal));
        }
    }

    /**
     * The registry the API talks to. Deliberately not the Spring {@code ShardOwnedQueueFactory} — that
     * lives in the starter and is tested there. Using the interface here is what proves the API layer
     * needs nothing from Spring.
     */
    private static final class TestMessageQueues implements MessageQueues, AutoCloseable {
        private final javax.sql.DataSource                      dataSource;
        private final Map<QueueName, PostgresqlMessageQueue> built = new LinkedHashMap<>();

        TestMessageQueues(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
        }

        PostgresqlMessageQueue queue(QueueName queueName) {
            return built.computeIfAbsent(queueName,
                                         name -> PostgresqlMessageQueue.builder()
                                                                       .setDataSource(dataSource)
                                                                       .setQueueName(name)
                                                                       .setInstanceId("api-test")
                                                                       .build());
        }

        @Override
        public List<QueueName> queueNames() throws SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public Optional<MessageQueue> findQueue(QueueName queueName) throws SQLException {
            return ShardOwnedSchema.resolve(dataSource, queueName)
                                   .map(registered -> queue(registered.name()));
        }

        @Override
        public void close() {
            built.values().forEach(PostgresqlMessageQueue::close);
        }
    }
}
