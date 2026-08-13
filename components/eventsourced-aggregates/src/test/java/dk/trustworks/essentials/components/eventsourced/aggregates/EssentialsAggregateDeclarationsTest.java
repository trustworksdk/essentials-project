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

package dk.trustworks.essentials.components.eventsourced.aggregates;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EssentialsAggregateDeclarationsTest {
    private static final AggregateType ORDERS    = AggregateType.of("Orders");
    private static final AggregateType CUSTOMERS = AggregateType.of("Customers");

    private static class Order {
    }

    private static class Customer {
    }

    @Test
    void test_declarations_are_returned_in_declaration_order() {
        var declarations = EssentialsAggregateDeclarations.builder()
                                                          .declare(ORDERS, Order.class)
                                                          .declare(CUSTOMERS, Customer.class)
                                                          .build();

        assertThat(declarations.declarations()).containsExactly(new AggregateDeclaration(ORDERS, Order.class),
                                                                new AggregateDeclaration(CUSTOMERS, Customer.class));
    }

    @Test
    void test_an_aggregate_type_can_be_looked_up_by_implementation_type() {
        var declarations = EssentialsAggregateDeclarations.builder()
                                                          .declare(ORDERS, Order.class)
                                                          .build();

        assertThat(declarations.findAggregateType(Order.class)).contains(ORDERS);
        assertThat(declarations.findAggregateType(Customer.class)).isEmpty();
    }

    @Test
    void test_declaring_the_same_pair_twice_is_idempotent() {
        var declarations = EssentialsAggregateDeclarations.builder()
                                                          .declare(ORDERS, Order.class)
                                                          .declare(ORDERS, Order.class)
                                                          .build();

        assertThat(declarations.declarations()).hasSize(1);
    }

    @Test
    void test_declaring_one_implementation_type_for_two_aggregate_types_is_rejected() {
        // The policy registries are keyed by implementation class, so the second declaration would silently displace
        // the first
        assertThatThrownBy(() -> EssentialsAggregateDeclarations.builder()
                                                                .declare(ORDERS, Order.class)
                                                                .declare(CUSTOMERS, Order.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Order.class.getName())
                .hasMessageContaining("Orders")
                .hasMessageContaining("Customers");
    }

    @Test
    void test_two_aggregate_types_may_share_neither_half() {
        assertThat(EssentialsAggregateDeclarations.of(new AggregateDeclaration(ORDERS, Order.class),
                                                      new AggregateDeclaration(CUSTOMERS, Customer.class))
                                                  .declarations()).hasSize(2);
    }

    @Test
    void test_a_declaration_rejects_null_arguments() {
        assertThatThrownBy(() -> new AggregateDeclaration(null, Order.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateDeclaration(ORDERS, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void test_declarations_are_immutable() {
        var declarations = EssentialsAggregateDeclarations.builder()
                                                          .declare(ORDERS, Order.class)
                                                          .build();

        assertThatThrownBy(() -> declarations.declarations().add(new AggregateDeclaration(CUSTOMERS, Customer.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
