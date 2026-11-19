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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2's commit paths: an append on the application's transaction wakes the event log after the
 * commit, through foundation's unit of work and through a Spring {@code TransactionSynchronization}; a rollback wakes
 * nothing. The backstop is 60 s, so a delivery within seconds is the wake's.
 */
class EventLogCommitsIT extends AbstractProcessorIT {
    private final Set<Long>  received = ConcurrentHashMap.newKeySet();
    private EventReactor     reactor;

    @BeforeEach
    void slowBackstop() throws SQLException {
        eventLog.stop();
        eventLog = EventLog.builder()
                           .setDataSource(engineDataSource)
                           .setSettings(EventLogSettings.defaults().withBackstopInterval(Duration.ofSeconds(60)).withCrossJvmWakeUp(false))
                           .build();
        eventLog.start();
        eventLog.registerAggregateType(ORDERS);
        reactor = EventReactor.builder("waker", ORDERS)
                              .ownership(Ownership.perInstance("node-1"))
                              .on(Added.class, event -> received.add(event.globalOrder().longValue()))
                              .build(eventLog, serializer)
                              .start();
        awaitTrue("attached", Duration.ofSeconds(10), () -> reactor.handle().orElseThrow().status().phase().equals("ATTACHED"));
    }

    @AfterEach
    void stopReactor() {
        reactor.stop();
    }

    private void append(Connection connection, String order) throws SQLException {
        var event = new EventToAppend(UUID.randomUUID(), null, null, "FQCN:" + Added.class.getName(), 1, Instant.now(), serializer.serialize(new Added(order, 1)),
                                      serializer.serialize(EventMetaData.empty()));
        eventLog.append(connection, AppendRequest.withoutExpectedOrder(ORDERS, order, List.of(event)));
    }

    @Test
    void a_spring_transaction_wakes_after_commit_and_a_rolled_back_one_never() throws Exception {
        var template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            try {
                append(DataSourceUtils.getConnection(pool), "o1");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            EventLogCommits.afterSpringCommit(eventLog, ORDERS);
            EventLogCommits.afterSpringCommit(eventLog, ORDERS);
            status.setRollbackOnly();
        });
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        template.executeWithoutResult(status -> {
            try {
                append(DataSourceUtils.getConnection(pool), "o2");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            EventLogCommits.afterSpringCommit(eventLog, ORDERS);
        });
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        awaitTrue("the committed append delivered", Duration.ofSeconds(10), () -> received.size() == 1);
        // the rolled-back append took global order 1 or none; only the committed one is delivered
        assertThat(received).hasSize(1);
    }

    @Test
    void a_unit_of_work_wakes_after_commit() {
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            append(unitOfWork.handle().getConnection(), "o3");
            EventLogCommits.afterCommit(unitOfWork, eventLog, ORDERS);
            EventLogCommits.afterCommit(unitOfWork, eventLog, ORDERS);
        });
        awaitTrue("the committed append delivered", Duration.ofSeconds(10), () -> received.size() == 1);
    }
}
