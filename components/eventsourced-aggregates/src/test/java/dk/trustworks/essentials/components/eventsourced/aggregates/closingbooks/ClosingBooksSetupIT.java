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
import dk.trustworks.essentials.types.CharSequenceType;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a builder-assembled {@link ClosingBooksSetup} - default Postgres generation repository, default stream-id
 * generator, derived generation access - rolls a generation end to end, with a typed
 * {@link dk.trustworks.essentials.types.SingleValueType} logical id rather than a String.
 */
@Testcontainers
class ClosingBooksSetupIT {
    private static final AggregateType ACCOUNTS = AggregateType.of("Accounts");

    /** Static, so the class starts one container rather than one per test method. */
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4").withDatabaseName("event-store")
                                                                                                               .withUsername("test-user")
                                                                                                               .withPassword("secret-password");

    private EventStoreManagedUnitOfWorkFactory                unitOfWorkFactory;
    private ClosingBooksSetup<AccountId, GenerationStreamId>  setup;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        // The container is shared across methods, so reset what this class writes
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS aggregate_generations");
            uow.handle().execute("DROP TABLE IF EXISTS custom_generations");
        });
        setup = ClosingBooksSetup.<AccountId, GenerationStreamId>builder(ACCOUNTS, TestAccount.class)
                                 .setLogicalAggregateIdType(AccountId.class)
                                 .setStreamIdType(GenerationStreamId.class)
                                 .setUnitOfWorkFactory(unitOfWorkFactory)
                                 .build();
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void a_builder_assembled_setup_rolls_a_generation_end_to_end() {
        var logicalAggregateId = new LogicalAggregateId<>(AccountId.of("ACC-IT-1"));

        var firstGeneration = setup.coordinator().resolveOrOpenCurrentGeneration(logicalAggregateId);

        assertThat(firstGeneration.generation()).isEqualTo(1L);
        assertThat(firstGeneration.streamAggregateId()).isEqualTo("ACC-IT-1#1");
        assertThat(firstGeneration.isOpen()).isTrue();

        var secondGeneration = setup.coordinator().closeAndOpenNextGeneration(logicalAggregateId);

        assertThat(secondGeneration.generation()).isEqualTo(2L);
        assertThat(secondGeneration.streamAggregateId()).isEqualTo("ACC-IT-1#2");
        assertThat(setup.coordinator().resolveCurrentGeneration(logicalAggregateId)).contains(secondGeneration);
        assertThat(setup.coordinator().loadGenerations(logicalAggregateId)).hasSize(2);
    }

    @Test
    void the_typed_logical_id_survives_a_round_trip_through_the_generation_table() {
        var logicalAggregateId = new LogicalAggregateId<>(AccountId.of("ACC-IT-2"));

        setup.coordinator().resolveOrOpenCurrentGeneration(logicalAggregateId);

        assertThat(setup.coordinator().resolveCurrentGeneration(logicalAggregateId))
                .hasValueSatisfying(generation -> assertThat((Object) generation.logicalAggregateId().value())
                        .isEqualTo(AccountId.of("ACC-IT-2")));
    }

    @Test
    void the_derived_generation_access_reads_the_generations_from_postgres() {
        var logicalAggregateId = new LogicalAggregateId<>(AccountId.of("ACC-IT-3"));
        setup.coordinator().resolveOrOpenCurrentGeneration(logicalAggregateId);
        setup.coordinator().closeAndOpenNextGeneration(logicalAggregateId);

        // Exactly the shape the admin API uses: the logical id arrives as a String
        assertThat(setup.generationAccess().resolveCurrentGeneration("ACC-IT-3"))
                .hasValueSatisfying(generation -> {
                    assertThat(generation.generation()).isEqualTo(2L);
                    assertThat(generation.streamAggregateId()).isEqualTo("ACC-IT-3#2");
                });
        assertThat(setup.generationAccess().loadGenerations("ACC-IT-3")).hasSize(2);
    }

    @Test
    void an_explicit_generation_repository_table_name_is_used() {
        var customTableSetup = ClosingBooksSetup.<AccountId, GenerationStreamId>builder(ACCOUNTS, TestAccount.class)
                                                .setLogicalAggregateIdType(AccountId.class)
                                                .setStreamIdType(GenerationStreamId.class)
                                                .setGenerationRepositoryTableName(Optional.of("custom_generations"))
                                                .setUnitOfWorkFactory(unitOfWorkFactory)
                                                .build();

        var generation = customTableSetup.coordinator()
                                         .resolveOrOpenCurrentGeneration(new LogicalAggregateId<>(AccountId.of("ACC-IT-4")));

        assertThat(generation.generation()).isEqualTo(1L);
        var rowCount = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                  .createQuery("SELECT count(*) FROM custom_generations")
                                                                  .mapTo(Integer.class)
                                                                  .one());
        assertThat(rowCount).isEqualTo(1);
    }

    static class AccountId extends CharSequenceType<AccountId> {
        AccountId(CharSequence value) {
            super(value);
        }

        static AccountId of(CharSequence value) {
            return new AccountId(value);
        }
    }

    static class GenerationStreamId extends CharSequenceType<GenerationStreamId> {
        GenerationStreamId(CharSequence value) {
            super(value);
        }

        static GenerationStreamId of(CharSequence value) {
            return new GenerationStreamId(value);
        }
    }

    static class TestAccount {
    }
}
