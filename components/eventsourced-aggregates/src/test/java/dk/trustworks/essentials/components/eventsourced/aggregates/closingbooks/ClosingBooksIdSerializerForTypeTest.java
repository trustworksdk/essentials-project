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

import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * One case per row of the {@link ClosingBooksIdSerializer#forType(Class)} strategy table, plus the round-trip property
 * and the fail-fast cases.
 * <p>
 * The id fixtures are local rather than the ones in {@code types/src/test}: reaching those would mean adding a test-jar
 * dependency on {@code types}, which is a heavier change than restating four small classes.
 */
class ClosingBooksIdSerializerForTypeTest {

    // ------------------------------------------------------------------------------------------------------
    // Row: String
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_string_id_round_trips_through_the_identity_strategy() {
        var serializer = ClosingBooksIdSerializer.forType(String.class);

        assertThat(serializer.serialize("Account-123")).isEqualTo("Account-123");
        assertThat(serializer.deserialize("Account-123")).isEqualTo("Account-123");
    }

    @Test
    void test_a_char_sequence_id_is_treated_as_a_string() {
        var serializer = ClosingBooksIdSerializer.forType(CharSequence.class);

        assertThat(serializer.deserialize("Account-123")).isEqualTo("Account-123");
    }

    // ------------------------------------------------------------------------------------------------------
    // Row: UUID
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_uuid_id_round_trips() {
        var serializer = ClosingBooksIdSerializer.forType(UUID.class);
        var id         = UUID.fromString("6c1c9c8e-2d0f-4a1e-9c1a-5f5a8f2a1b3c");

        assertThat(serializer.serialize(id)).isEqualTo("6c1c9c8e-2d0f-4a1e-9c1a-5f5a8f2a1b3c");
        assertThat(serializer.deserialize(serializer.serialize(id))).isEqualTo(id);
    }

    // ------------------------------------------------------------------------------------------------------
    // Row: enum
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_an_enum_id_round_trips_through_its_name() {
        var serializer = ClosingBooksIdSerializer.forType(Region.class);

        assertThat(serializer.serialize(Region.EMEA)).isEqualTo("EMEA");
        assertThat(serializer.deserialize("APAC")).isEqualTo(Region.APAC);
    }

    // ------------------------------------------------------------------------------------------------------
    // Row: SingleValueType over a CharSequence
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_char_sequence_backed_single_value_type_round_trips() {
        var serializer = ClosingBooksIdSerializer.forType(AccountId.class);
        var id         = AccountId.of("ACC-DEMO-001");

        assertThat(serializer.serialize(id)).isEqualTo("ACC-DEMO-001");
        // Cast because AccountId is a CharSequence, which makes the assertThat overload ambiguous
        assertThat((Object) serializer.deserialize("ACC-DEMO-001")).isEqualTo(id);
    }

    // ------------------------------------------------------------------------------------------------------
    // Row: SingleValueType over a non-string value - the row that needs the value type resolved
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_long_backed_single_value_type_round_trips() {
        // The interesting case: SingleValueType.fromObject would look for a Long-arg creator and be handed a String, so
        // the persisted value has to be parsed into Long first
        var serializer = ClosingBooksIdSerializer.forType(SequenceId.class);
        var id         = SequenceId.of(4711L);

        assertThat(serializer.serialize(id)).isEqualTo("4711");
        assertThat(serializer.deserialize("4711")).isEqualTo(id);
    }

    @Test
    void test_a_big_decimal_backed_single_value_type_round_trips() {
        var serializer = ClosingBooksIdSerializer.forType(LedgerNumber.class);
        var id         = LedgerNumber.of(new BigDecimal("100.50"));

        assertThat(serializer.serialize(id)).isEqualTo("100.50");
        assertThat(serializer.deserialize("100.50")).isEqualTo(id);
    }

    // ------------------------------------------------------------------------------------------------------
    // Row: anything else - reflective creator
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_plain_type_with_a_static_of_method_round_trips() {
        var serializer = ClosingBooksIdSerializer.forType(PlainIdWithStaticOf.class);

        assertThat(serializer.serialize(PlainIdWithStaticOf.of("abc"))).isEqualTo("abc");
        assertThat(serializer.deserialize("abc")).isEqualTo(PlainIdWithStaticOf.of("abc"));
    }

    @Test
    void test_a_plain_type_with_a_string_constructor_round_trips() {
        var serializer = ClosingBooksIdSerializer.forType(PlainIdWithConstructor.class);

        assertThat(serializer.deserialize("abc")).isEqualTo(new PlainIdWithConstructor("abc"));
    }

    // ------------------------------------------------------------------------------------------------------
    // Fail fast
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_a_type_with_no_usable_creator_fails_at_for_type_time_naming_the_shapes_searched_for() {
        assertThatThrownBy(() -> ClosingBooksIdSerializer.forType(UnconstructibleId.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(UnconstructibleId.class.getName())
                .hasMessageContaining("UnconstructibleId(String) constructor")
                .hasMessageContaining("static UnconstructibleId of(String)")
                .hasMessageContaining("static UnconstructibleId from(String)");
    }

    @Test
    void test_a_single_value_type_over_an_unparseable_value_fails_at_for_type_time() {
        assertThatThrownBy(() -> ClosingBooksIdSerializer.forType(ByteArrayId.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ByteArrayId.class.getName())
                .hasMessageContaining("cannot be parsed from a String")
                .hasMessageContaining("BigDecimal");
    }

    @Test
    void test_for_type_rejects_a_null_id_type() {
        assertThatThrownBy(() -> ClosingBooksIdSerializer.forType(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------
    // Cross-cutting properties
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_every_derived_serializer_round_trips_and_wraps_a_logical_aggregate_id_consistently() {
        assertRoundTrip(String.class, "Account-123");
        assertRoundTrip(UUID.class, UUID.fromString("6c1c9c8e-2d0f-4a1e-9c1a-5f5a8f2a1b3c"));
        assertRoundTrip(Region.class, Region.AMER);
        assertRoundTrip(AccountId.class, AccountId.of("ACC-1"));
        assertRoundTrip(SequenceId.class, SequenceId.of(42L));
        assertRoundTrip(LedgerNumber.class, LedgerNumber.of(new BigDecimal("1.25")));
        assertRoundTrip(PlainIdWithStaticOf.class, PlainIdWithStaticOf.of("xyz"));
    }

    private <ID> void assertRoundTrip(Class<ID> idType, ID id) {
        var serializer = ClosingBooksIdSerializer.forType(idType);

        assertThat(serializer.deserialize(serializer.serialize(id)))
                .describedAs("round trip of %s", idType.getSimpleName())
                .isEqualTo(id);

        var logicalAggregateId = new LogicalAggregateId<>(id);
        assertThat(serializer.serializeLogicalAggregateId(logicalAggregateId))
                .describedAs("a logical aggregate id persists exactly as the id it wraps, for %s", idType.getSimpleName())
                .isEqualTo(serializer.serialize(id));
        assertThat(serializer.deserializeLogicalAggregateId(serializer.serializeLogicalAggregateId(logicalAggregateId)))
                .isEqualTo(logicalAggregateId);
    }

    @Test
    void test_of_rejects_null_functions_and_null_arguments() {
        assertThatThrownBy(() -> ClosingBooksIdSerializer.of(null, persisted -> persisted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClosingBooksIdSerializer.of(Object::toString, null)).isInstanceOf(IllegalArgumentException.class);

        var serializer = ClosingBooksIdSerializer.of((String id) -> id, persisted -> persisted);
        assertThatThrownBy(() -> serializer.serialize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.deserialize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------------

    enum Region {
        EMEA, APAC, AMER
    }

    static class AccountId extends CharSequenceType<AccountId> implements Identifier {
        AccountId(CharSequence value) {
            super(value);
        }

        static AccountId of(CharSequence value) {
            return new AccountId(value);
        }
    }

    static class SequenceId extends LongType<SequenceId> implements Identifier {
        SequenceId(Long value) {
            super(value);
        }

        static SequenceId of(long value) {
            return new SequenceId(value);
        }
    }

    static class LedgerNumber extends BigDecimalType<LedgerNumber> implements Identifier {
        LedgerNumber(BigDecimal value) {
            super(value);
        }

        static LedgerNumber of(BigDecimal value) {
            return new LedgerNumber(value);
        }
    }

    /** A {@link SingleValueType} whose value type has no String parser. */
    static class ByteArrayId implements SingleValueType<byte[], ByteArrayId> {
        private final byte[] value;

        ByteArrayId(byte[] value) {
            this.value = value;
        }

        @Override
        public byte[] value() {
            return value;
        }

        @Override
        public int compareTo(ByteArrayId other) {
            return 0;
        }
    }

    static class PlainIdWithStaticOf {
        private final String value;

        private PlainIdWithStaticOf(String value) {
            this.value = value;
        }

        static PlainIdWithStaticOf of(String value) {
            return new PlainIdWithStaticOf(value);
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PlainIdWithStaticOf that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    static class PlainIdWithConstructor {
        private final String value;

        PlainIdWithConstructor(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PlainIdWithConstructor that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    /** No String-accepting constructor, no static {@code of}/{@code from}. */
    static class UnconstructibleId {
        UnconstructibleId(int ignored) {
        }
    }
}
