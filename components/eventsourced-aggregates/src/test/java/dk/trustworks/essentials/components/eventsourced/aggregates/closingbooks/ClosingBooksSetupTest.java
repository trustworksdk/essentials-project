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
import dk.trustworks.essentials.types.CharSequenceType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Builder-level coverage for {@link ClosingBooksSetup}. No Docker: the generation repository is the in-memory one, so
 * the Postgres default is never constructed.
 */
class ClosingBooksSetupTest {
    private static final AggregateType ACCOUNTS = AggregateType.of("Accounts");

    private ClosingBooksSetupBuilder<AccountId, GenerationStreamId> builderWithInMemoryRepository() {
        return ClosingBooksSetup.<AccountId, GenerationStreamId>builder(ACCOUNTS, TestAccount.class)
                                .setLogicalAggregateIdType(AccountId.class)
                                .setStreamIdType(GenerationStreamId.class)
                                .setGenerationRepository(new InMemoryClosingBooksGenerationResolver<>())
                                .setUnitOfWorkFactory(InlineUnitOfWorkFactories.inline());
    }

    @Test
    void test_the_setup_exposes_the_assembled_parts() {
        var setup = builderWithInMemoryRepository().build();

        assertThat((Object) setup.aggregateType()).isEqualTo(ACCOUNTS);
        assertThat(setup.aggregateImplementationType()).isEqualTo(TestAccount.class);
        assertThat(setup.generationRepository()).isNotNull();
        assertThat(setup.coordinator()).isNotNull();
        assertThat(setup.logicalAggregateIdSerializer()).isNotNull();
        assertThat(setup.streamIdSerializer()).isNotNull();
    }

    @Test
    void test_the_id_type_setters_derive_working_serializers() {
        var setup = builderWithInMemoryRepository().build();

        assertThat(setup.logicalAggregateIdSerializer().serialize(AccountId.of("ACC-1"))).isEqualTo("ACC-1");
        assertThat((Object) setup.logicalAggregateIdSerializer().deserialize("ACC-1")).isEqualTo(AccountId.of("ACC-1"));
        assertThat((Object) setup.streamIdSerializer().deserialize("ACC-1#2")).isEqualTo(GenerationStreamId.of("ACC-1#2"));
    }

    @Test
    void test_generation_access_is_derived_and_carries_the_aggregate_type_and_implementation_class() {
        var setup = builderWithInMemoryRepository().build();

        var generationAccess = setup.generationAccess();

        assertThat((Object) generationAccess.aggregateType()).isEqualTo(ACCOUNTS);
        assertThat(generationAccess.aggregateImplementationType()).isEqualTo(TestAccount.class);
        assertThat(generationAccess.generationRepository()).isSameAs(setup.generationRepository());
        assertThat(generationAccess.logicalAggregateIdSerializer()).isSameAs(setup.logicalAggregateIdSerializer());
    }

    @Test
    void test_generation_access_reads_generations_back_through_the_string_based_admin_api_shape() {
        var setup = builderWithInMemoryRepository().build();
        var logicalAggregateId = new LogicalAggregateId<>(AccountId.of("ACC-1"));

        setup.coordinator().resolveOrOpenCurrentGeneration(logicalAggregateId);

        // The admin API only ever passes the id as a String - the derived access has to deserialize it
        assertThat(setup.generationAccess().resolveCurrentGeneration("ACC-1"))
                .hasValueSatisfying(generation -> {
                    assertThat(generation.generation()).isEqualTo(1L);
                    assertThat(generation.streamAggregateId()).isEqualTo("ACC-1#1");
                });
        assertThat(setup.generationAccess().loadGenerations("ACC-1")).hasSize(1);
    }

    @Test
    void test_the_default_stream_id_generator_is_logical_id_hash_generation() {
        var setup = builderWithInMemoryRepository().build();

        var generation = setup.coordinator().resolveOrOpenCurrentGeneration(new LogicalAggregateId<>(AccountId.of("ACC-7")));

        assertThat(generation.streamAggregateId()).isEqualTo("ACC-7#1");
    }

    @Test
    void test_an_explicit_stream_id_generator_is_used_instead() {
        var setup = builderWithInMemoryRepository()
                .setStreamIdGenerator((aggregateType, logicalAggregateId, nextGeneration) -> logicalAggregateId.value() + "/gen-" + nextGeneration)
                .build();

        var generation = setup.coordinator().resolveOrOpenCurrentGeneration(new LogicalAggregateId<>(AccountId.of("ACC-7")));

        assertThat(generation.streamAggregateId()).isEqualTo("ACC-7/gen-1");
    }

    @Test
    void test_a_rollover_opens_the_next_generation_with_the_default_stream_id_format() {
        var setup = builderWithInMemoryRepository().build();
        var logicalAggregateId = new LogicalAggregateId<>(AccountId.of("ACC-9"));
        setup.coordinator().resolveOrOpenCurrentGeneration(logicalAggregateId);

        var next = setup.coordinator().closeAndOpenNextGeneration(logicalAggregateId);

        assertThat(next.generation()).isEqualTo(2L);
        assertThat(next.streamAggregateId()).isEqualTo("ACC-9#2");
    }

    @Test
    void test_the_clock_and_meter_registry_setters_are_accepted() {
        var setup = builderWithInMemoryRepository()
                .setClock(Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC))
                .setMeterRegistry(new SimpleMeterRegistry())
                .build();

        assertThat(setup.coordinator()).isNotNull();
    }

    @Test
    void test_explicit_serializers_can_be_given_instead_of_id_types() {
        var setup = ClosingBooksSetup.<String, String>builder(ACCOUNTS, TestAccount.class)
                                     .setLogicalAggregateIdSerializer(ClosingBooksIdSerializer.stringBased())
                                     .setStreamIdSerializer(ClosingBooksIdSerializer.stringBased())
                                     .setGenerationRepository(new InMemoryClosingBooksGenerationResolver<>())
                                     .setUnitOfWorkFactory(InlineUnitOfWorkFactories.inline())
                                     .build();

        assertThat(setup.logicalAggregateIdSerializer().serialize("ACC-1")).isEqualTo("ACC-1");
    }

    // ------------------------------------------------------------------------------------------------------
    // Missing-required-setter messages
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_missing_logical_aggregate_id_serializer_names_both_ways_to_supply_one() {
        assertThatThrownBy(() -> ClosingBooksSetup.<AccountId, GenerationStreamId>builder(ACCOUNTS, TestAccount.class)
                                                  .setStreamIdType(GenerationStreamId.class)
                                                  .setUnitOfWorkFactory(InlineUnitOfWorkFactories.inline())
                                                  .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setLogicalAggregateIdType")
                .hasMessageContaining("setLogicalAggregateIdSerializer");
    }

    @Test
    void test_a_missing_stream_id_serializer_names_both_ways_to_supply_one() {
        assertThatThrownBy(() -> ClosingBooksSetup.<AccountId, GenerationStreamId>builder(ACCOUNTS, TestAccount.class)
                                                  .setLogicalAggregateIdType(AccountId.class)
                                                  .setUnitOfWorkFactory(InlineUnitOfWorkFactories.inline())
                                                  .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setStreamIdType")
                .hasMessageContaining("setStreamIdSerializer");
    }

    @Test
    void test_a_missing_unit_of_work_factory_is_rejected_even_when_a_generation_repository_is_given() {
        // The coordinator runs close-and-open-next in a single unit of work, so supplying a repository does not remove
        // the need for a factory
        assertThatThrownBy(() -> ClosingBooksSetup.<AccountId, GenerationStreamId>builder(ACCOUNTS, TestAccount.class)
                                                  .setLogicalAggregateIdType(AccountId.class)
                                                  .setStreamIdType(GenerationStreamId.class)
                                                  .setGenerationRepository(new InMemoryClosingBooksGenerationResolver<>())
                                                  .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setUnitOfWorkFactory");
    }

    @Test
    void test_the_builder_rejects_null_arguments() {
        assertThatThrownBy(() -> ClosingBooksSetup.builder(null, TestAccount.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClosingBooksSetup.builder(ACCOUNTS, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builderWithInMemoryRepository().setStreamIdGenerator(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builderWithInMemoryRepository().setClock(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builderWithInMemoryRepository().setMeterRegistry((Optional<io.micrometer.core.instrument.MeterRegistry>) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void test_an_id_type_with_no_derivable_strategy_fails_when_the_setter_is_called() {
        assertThatThrownBy(() -> ClosingBooksSetup.<UnconstructibleId, String>builder(ACCOUNTS, TestAccount.class)
                                                  .setLogicalAggregateIdType(UnconstructibleId.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(UnconstructibleId.class.getName());
    }

    // ------------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------------

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

    static class UnconstructibleId {
        UnconstructibleId(int ignored) {
        }
    }
}
