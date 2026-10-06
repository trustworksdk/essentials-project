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

package dk.trustworks.essentials.types;


import dk.trustworks.essentials.shared.reflection.*;
import dk.trustworks.essentials.types.ids.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleValueTypeTest {
    @Test
    void test_from_with_AccountId_and_long_value() {
        var accountId = SingleValueType.from(100L, AccountId.class);
        assertThat(accountId).isNotNull();
        assertThat(accountId).isInstanceOf(AccountId.class);
        assertThat(accountId.value()).isEqualTo(100L);
    }

    @Test
    void test_fromObject_with_AccountId_and_String_value() {
        var accountId = SingleValueType.fromObject("100", AccountId.class);
        assertThat(accountId).isNotNull();
        assertThat(accountId).isInstanceOf(AccountId.class);
        assertThat(accountId.value()).isEqualTo(100L);
    }

    @Test
    void test_from_with_CustomerId_and_String_value() {
        var customerId = SingleValueType.from("id", CustomerId.class);
        assertThat((CharSequence) customerId).isNotNull();
        assertThat((CharSequence) customerId).isInstanceOf(CustomerId.class);
        assertThat(customerId.value()).isEqualTo("id");
    }

    @Test
    void test_fromObject_with_CustomerId_and_String_value() {
        var customerId = SingleValueType.fromObject("id", CustomerId.class);
        assertThat((CharSequence) customerId).isNotNull();
        assertThat((CharSequence) customerId).isInstanceOf(CustomerId.class);
        assertThat(customerId.value()).isEqualTo("id");
    }

    @Test
    void test_from_with_TransactionId_and_String_value() {
        var transactionId = SingleValueType.from("id", TransactionId.class);
        assertThat((CharSequence) transactionId).isNotNull();
        assertThat((CharSequence) transactionId).isInstanceOf(TransactionId.class);
        assertThat(transactionId.value()).isEqualTo("id");
    }

    @Test
    void test_fromObject_with_TransactionId_and_String_value() {
        var transactionId = SingleValueType.fromObject("id", TransactionId.class);
        assertThat((CharSequence) transactionId).isNotNull();
        assertThat((CharSequence) transactionId).isInstanceOf(TransactionId.class);
        assertThat(transactionId.value()).isEqualTo("id");
    }

    @Test
    void test_from_with_MessageId_and_String_value() {
        var transactionId = SingleValueType.from("id", MessageId.class);
        assertThat((CharSequence) transactionId).isNotNull();
        assertThat((CharSequence) transactionId).isInstanceOf(MessageId.class);
        assertThat(transactionId.value()).isEqualTo("id");
    }

    @Test
    void test_fromObject_with_MessageId_and_String_value() {
        var transactionId = SingleValueType.fromObject("id", MessageId.class);
        assertThat((CharSequence) transactionId).isNotNull();
        assertThat((CharSequence) transactionId).isInstanceOf(MessageId.class);
        assertThat(transactionId.value()).isEqualTo("id");
    }

    @Test
    void fromObject_throws_TooManyMatchingMethodsFoundException_when_two_static_of_methods_match() {
        assertThatThrownBy(() -> SingleValueType.fromObject("id", AmbiguousOfId.class))
                .isExactlyInstanceOf(TooManyMatchingMethodsFoundException.class)
                .hasMessageContaining("Found 2 static methods")
                .hasMessageContaining(AmbiguousOfId.class.getName())
                .hasMessageContaining("name 'of'");
    }

    @Test
    void fromObject_throws_ReflectionException_naming_the_type_when_nothing_matches() {
        assertThatThrownBy(() -> SingleValueType.fromObject(42, CustomerId.class))
                .isExactlyInstanceOf(ReflectionException.class)
                .hasMessageStartingWith("Failed to create instance of '" + CustomerId.class.getName() + "' from value of type 'java.lang.Integer'. ")
                .hasMessageContaining("CustomerId(java.lang.Integer) constructor")
                .hasMessageNotContaining("{:");
    }

    /**
     * Two constructors accept a String, so the constructor step finds no single match and falls through to
     * the static <code>of</code> lookup - where both overloads accept a String as well
     */
    public static class AmbiguousOfId extends CharSequenceType<AmbiguousOfId> {
        public AmbiguousOfId(CharSequence value) {
            super(value);
        }

        public AmbiguousOfId(String value) {
            super(value);
        }

        public static AmbiguousOfId of(CharSequence value) {
            return new AmbiguousOfId(value);
        }

        public static AmbiguousOfId of(String value) {
            return new AmbiguousOfId(value);
        }
    }
}
