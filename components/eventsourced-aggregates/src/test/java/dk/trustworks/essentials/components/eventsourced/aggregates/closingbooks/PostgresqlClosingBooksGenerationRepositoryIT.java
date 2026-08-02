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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresqlClosingBooksGenerationRepositoryIT {
    private static final AggregateType ACCOUNTS = AggregateType.of("Accounts");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest").withDatabaseName("event-store")
                                                                                                           .withUsername("test-user")
                                                                                                           .withPassword("secret-password");

    private EventStoreManagedUnitOfWorkFactory            unitOfWorkFactory;
    private PostgresqlClosingBooksGenerationRepository<String> repository;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        repository = new PostgresqlClosingBooksGenerationRepository<>(unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void open_resolve_close_and_reopen_generations() {
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");

        var firstGeneration = repository.openNextGeneration(ACCOUNTS,
                                                            logicalAggregateId,
                                                            "Account-123#1");

        assertThat(firstGeneration.generation()).isEqualTo(1);
        assertThat(firstGeneration.isOpen()).isTrue();
        assertThat(repository.resolveCurrentGeneration(ACCOUNTS, logicalAggregateId))
                .contains(firstGeneration);

        var closedGeneration = repository.closeCurrentGeneration(ACCOUNTS, logicalAggregateId);

        assertThat(closedGeneration.generation()).isEqualTo(1);
        assertThat(closedGeneration.isClosed()).isTrue();
        assertThat(closedGeneration.closedAt()).isPresent();
        assertThat(repository.resolveCurrentGeneration(ACCOUNTS, logicalAggregateId)).isEmpty();

        var secondGeneration = repository.openNextGeneration(ACCOUNTS,
                                                             logicalAggregateId,
                                                             "Account-123#2");

        assertThat(secondGeneration.generation()).isEqualTo(2);
        assertThat(secondGeneration.isOpen()).isTrue();
        assertThat(repository.loadGenerations(ACCOUNTS, logicalAggregateId)).hasSize(2);
    }

    @Test
    void coordinator_close_and_open_next_generation_rolls_back_close_when_open_fails() {
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        repository.openNextGeneration(ACCOUNTS, logicalAggregateId, "Account-123#1");

        ClosingBooksStreamIdGenerator<String> failingStreamIdGenerator = (type, id, generation) -> {
            throw new RuntimeException("boom");
        };
        var coordinator = new ClosingBooksCoordinator<>(ACCOUNTS,
                                                        repository,
                                                        failingStreamIdGenerator,
                                                        unitOfWorkFactory);

        assertThatThrownBy(() -> coordinator.closeAndOpenNextGeneration(logicalAggregateId))
                .rootCause()
                .hasMessage("boom");

        // The close + open were inside one UoW. Since the open path threw, the entire transaction
        // rolled back — the original generation must still be OPEN, not stuck in CLOSED with no successor.
        assertThat(repository.resolveCurrentGeneration(ACCOUNTS, logicalAggregateId))
                .isPresent()
                .get()
                .satisfies(current -> {
                    assertThat(current.generation()).isEqualTo(1);
                    assertThat(current.isOpen()).isTrue();
                });
        assertThat(repository.loadGenerations(ACCOUNTS, logicalAggregateId)).hasSize(1);
    }

    @Test
    void cannot_open_a_new_generation_while_another_generation_is_open() {
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        repository.openNextGeneration(ACCOUNTS,
                                      logicalAggregateId,
                                      "Account-123#1");

        assertThatThrownBy(() -> repository.openNextGeneration(ACCOUNTS,
                                                               logicalAggregateId,
                                                               "Account-123#2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an open generation");
    }

    @Test
    void can_use_an_explicit_logical_aggregate_id_serializer_for_non_string_ids() {
        var typedRepository = new PostgresqlClosingBooksGenerationRepository<>(unitOfWorkFactory,
                                                                               java.util.Optional.empty(),
                                                                               new ClosingBooksLogicalAggregateIdSerializer<Integer>() {
                                                                                   @Override
                                                                                   public String serialize(LogicalAggregateId<Integer> logicalAggregateId) {
                                                                                       return logicalAggregateId.value().toString();
                                                                                   }

                                                                                   @Override
                                                                                   public LogicalAggregateId<Integer> deserialize(String serializedLogicalAggregateId) {
                                                                                       return new LogicalAggregateId<>(Integer.parseInt(serializedLogicalAggregateId));
                                                                                   }
                                                                               });
        var logicalAggregateId = new LogicalAggregateId<>(123);

        var firstGeneration = typedRepository.openNextGeneration(ACCOUNTS,
                                                                 logicalAggregateId,
                                                                 "Account-123#1");

        assertThat(firstGeneration.logicalAggregateId().value()).isEqualTo(123);
        assertThat(typedRepository.resolveCurrentGeneration(ACCOUNTS, logicalAggregateId))
                .hasValueSatisfying(generation -> assertThat(generation.logicalAggregateId().value()).isEqualTo(123));
        assertThat(typedRepository.loadOpenGenerations(ACCOUNTS, 10))
                .extracting(generation -> generation.logicalAggregateId().value())
                .contains(123);
    }

    @Test
    void a_deferred_generation_is_excluded_from_scan_batches_until_its_deadline() {
        var deferred = new LogicalAggregateId<>("Account-deferred");
        var eligible = new LogicalAggregateId<>("Account-eligible");
        repository.openNextGeneration(ACCOUNTS, deferred, "Account-deferred#1");
        repository.openNextGeneration(ACCOUNTS, eligible, "Account-eligible#1");

        var now = OffsetDateTime.now();
        repository.deferScan(ACCOUNTS, deferred, now.plusMinutes(5));

        assertThat(repository.loadOpenGenerations(ACCOUNTS, 10, now))
                .extracting(AggregateGeneration::streamAggregateId)
                .containsExactly("Account-eligible#1");

        assertThat(repository.loadOpenGenerations(ACCOUNTS, 10, now.plusMinutes(6)))
                .extracting(AggregateGeneration::streamAggregateId)
                .containsExactlyInAnyOrder("Account-deferred#1", "Account-eligible#1");

        // The overload without an eligibility cut-off is unfiltered, so an explicit rollover is never blocked by a
        // deferral.
        assertThat(repository.loadOpenGenerations(ACCOUNTS, 10))
                .extracting(AggregateGeneration::streamAggregateId)
                .containsExactlyInAnyOrder("Account-deferred#1", "Account-eligible#1");
    }

    @Test
    void opening_the_next_generation_clears_a_deferral() {
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        repository.openNextGeneration(ACCOUNTS, logicalAggregateId, "Account-123#1");

        var now = OffsetDateTime.now();
        repository.deferScan(ACCOUNTS, logicalAggregateId, now.plusMinutes(5));
        assertThat(repository.loadOpenGenerations(ACCOUNTS, 10, now)).isEmpty();

        repository.closeCurrentGeneration(ACCOUNTS, logicalAggregateId);
        repository.openNextGeneration(ACCOUNTS, logicalAggregateId, "Account-123#2");

        assertThat(repository.loadOpenGenerations(ACCOUNTS, 10, now))
                .describedAs("the new generation is a fresh scan target")
                .extracting(AggregateGeneration::streamAggregateId)
                .containsExactly("Account-123#2");
    }

    @Test
    void deferring_a_generation_that_is_no_longer_open_is_a_no_op() {
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        repository.openNextGeneration(ACCOUNTS, logicalAggregateId, "Account-123#1");
        repository.closeCurrentGeneration(ACCOUNTS, logicalAggregateId);

        assertThatCode(() -> repository.deferScan(ACCOUNTS, logicalAggregateId, OffsetDateTime.now().plusMinutes(5)))
                .doesNotThrowAnyException();
    }
}
