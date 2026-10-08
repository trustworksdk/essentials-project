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

package dk.trustworks.essentials.components.queue.postgresql;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Where an enqueue's transaction boundary is on {@link PostgresqlDurableQueues}: it rolls back with an Essentials
 * {@link UnitOfWork} and with a plain Spring {@link TransactionTemplate}, and outside both it commits on its own - or,
 * with {@code requireActiveUnitOfWorkForEnqueue}, fails (RN-2).
 */
@Testcontainers
class EnqueueTransactionBoundaryIT {
    private static final QueueName QUEUE = QueueName.of("EnqueueBoundary");

    @Container
    static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("queue-db");

    private HikariDataSource        dataSource;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
        Jdbi.create(dataSource).useHandle(handle -> handle.execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------ rollback

    @Test
    void an_enqueue_rolls_back_with_an_essentials_unit_of_work() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        durableQueues = queues(unitOfWorkFactory, false);

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, Message.of("rolled-back"));
            durableQueues.queueMessages(QUEUE, List.of(Message.of("rolled-back-too")));
            throw new IllegalStateException("the caller's own work failed");
        })).hasRootCauseMessage("the caller's own work failed");

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    @Test
    void an_enqueue_rolls_back_with_a_spring_transaction_template() {
        var spring = new SpringSetUp(dataSource);
        durableQueues = queues(spring.unitOfWorkFactory, false);

        assertThatThrownBy(() -> spring.transactionTemplate.executeWithoutResult(status -> {
            durableQueues.queueMessage(QUEUE, Message.of("rolled-back"));
            durableQueues.queueMessages(QUEUE, List.of(Message.of("rolled-back-too")));
            throw new IllegalStateException("the caller's own work failed");
        })).hasMessage("the caller's own work failed");

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    // --------------------------------------------- outside a unit of work

    @Test
    void by_default_an_enqueue_outside_a_unit_of_work_commits_on_its_own() {
        durableQueues = queues(new JdbiUnitOfWorkFactory(Jdbi.create(dataSource)), false);

        durableQueues.queueMessage(QUEUE, Message.of("standalone"));
        durableQueues.queueMessages(QUEUE, List.of(Message.of("standalone-too")));

        assertThat(durableQueues.isRequireActiveUnitOfWorkForEnqueue()).isFalse();
        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isEqualTo(2);
    }

    @Test
    void when_required_an_enqueue_outside_a_unit_of_work_fails_and_queues_nothing() {
        durableQueues = queues(new JdbiUnitOfWorkFactory(Jdbi.create(dataSource)), true);

        assertThatThrownBy(() -> durableQueues.queueMessage(QUEUE, Message.of("refused")))
                .isInstanceOf(NoActiveUnitOfWorkException.class)
                .hasMessageContaining(EnqueueOutsideUnitOfWorkGuard.REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY);
        assertThatThrownBy(() -> durableQueues.queueMessages(QUEUE, List.of(Message.of("refused-too"))))
                .isInstanceOf(NoActiveUnitOfWorkException.class);

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero();
    }

    @Test
    void when_required_an_enqueue_inside_an_essentials_unit_of_work_goes_ahead() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        durableQueues = queues(unitOfWorkFactory, true);

        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("inside")));

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isEqualTo(1);
    }

    @Test
    void when_required_an_enqueue_inside_a_plain_spring_transaction_goes_ahead() {
        var spring = new SpringSetUp(dataSource);
        durableQueues = queues(spring.unitOfWorkFactory, true);

        // No Essentials UnitOfWork is bound yet under a bare TransactionTemplate - the guard must still see the transaction
        spring.transactionTemplate.executeWithoutResult(status -> {
            durableQueues.queueMessage(QUEUE, Message.of("inside"));
            durableQueues.queueMessages(QUEUE, List.of(Message.of("inside-too")));
        });

        assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isEqualTo(2);
    }

    private static PostgresqlDurableQueues queues(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                  boolean requireActiveUnitOfWorkForEnqueue) {
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setRequireActiveUnitOfWorkForEnqueue(requireActiveUnitOfWorkForEnqueue)
                                                   .build();
        durableQueues.start();
        return durableQueues;
    }

    /**
     * Wired as {@code spring-boot-starter-postgresql} wires it.
     */
    private static final class SpringSetUp {
        final TransactionTemplate                         transactionTemplate;
        final SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory;

        SpringSetUp(javax.sql.DataSource dataSource) {
            var transactionManager = new DataSourceTransactionManager(dataSource);
            transactionTemplate = new TransactionTemplate(transactionManager);
            unitOfWorkFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(new TransactionAwareDataSourceProxy(dataSource)),
                                                                                transactionManager);
        }
    }
}
