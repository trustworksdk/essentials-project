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

package dk.trustworks.essentials.shared.types;

import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.*;


class GenericTypeTest {
    @Test
    void test_using_a_Class_as_argument() {

        var genericType = new GenericType<String>(){};
        assertThat(genericType.getType()).isEqualTo(String.class);
        assertThat(genericType.getGenericType()).isEqualTo(String.class);
    }

    @Test
    void test_resolveGenericType_with_Class_as_argument() {
        var genericType = new GenericType<String>(){};
        var actualTypeOption      = GenericType.resolveGenericTypeOnSuperClass(genericType.getClass(), 0);
        assertThat(actualTypeOption).isNotNull();
        assertThat(actualTypeOption).isEqualTo(String.class);
    }

    @Test
    void test_using_a_ParameterizedType_as_argument() {
        var genericType = new GenericType<TestSubject<String>>(){};
        assertThat(genericType.getType()).isEqualTo(TestSubject.class);
        assertThat(genericType.getGenericType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType)genericType.getGenericType()).getRawType()).isEqualTo(TestSubject.class);
    }

    @Test
    void test_resolveGenericTypeOnSuperClass_with_ParameterizedType_as_argument() {
        var genericType = new GenericType<TestSubject<String>>(){};
        var actualTypeOption      = GenericType.resolveGenericTypeOnSuperClass(genericType.getClass(), 0);
        assertThat(actualTypeOption).isNotNull();
        assertThat(actualTypeOption).isEqualTo(TestSubject.class);
    }

    @Test
    void test_resolveGenericTypeForInterface() {
        var actualTypeWithIndex0 = GenericType.resolveGenericTypeForInterface(Order.class,
                                                                    WithState.class,
                                                                    0);
        var actualTypeWithIndex1 = GenericType.resolveGenericTypeForInterface(Order.class,
                                                                    WithState.class,
                                                                    1);
        var actualTypeWithIndex2 = GenericType.resolveGenericTypeForInterface(Order.class,
                                                                    WithState.class,
                                                                    2);
        assertThat(actualTypeWithIndex0).isEqualTo(String.class);
        assertThat(actualTypeWithIndex1).isEqualTo(Order.class);
        assertThat(actualTypeWithIndex2).isEqualTo(OrderState.class);
    }

    @Test
    void resolveTypeArgument_on_a_type_that_implements_the_interface_directly() {
        assertThat(GenericType.resolveTypeArgument(Order.class, WithState.class, 0)).contains(String.class);
        assertThat(GenericType.resolveTypeArgument(Order.class, WithState.class, 1)).contains(Order.class);
        assertThat(GenericType.resolveTypeArgument(Order.class, WithState.class, 2)).contains(OrderState.class);
    }

    @Test
    void resolveTypeArgument_on_a_subclass_of_a_type_that_implements_the_interface() {
        // resolveGenericTypeForInterface only reads the type's own interfaces and throws here
        assertThatThrownBy(() -> GenericType.resolveGenericTypeForInterface(SpecialOrder.class, WithState.class, 2))
                .isInstanceOf(IllegalStateException.class);

        assertThat(GenericType.resolveTypeArgument(SpecialOrder.class, WithState.class, 2)).contains(OrderState.class);
    }

    @Test
    void resolveTypeArgument_maps_type_variables_through_a_generic_base_class() {
        assertThat(GenericType.resolveTypeArgument(ProductOrder.class, WithState.class, 0)).contains(Long.class);
        assertThat(GenericType.resolveTypeArgument(ProductOrder.class, WithState.class, 2)).contains(ProductOrderState.class);
    }

    @Test
    void resolveTypeArgument_maps_type_variables_through_a_sub_interface_that_reorders_them() {
        assertThat(GenericType.resolveTypeArgument(Shipment.class, WithState.class, 0)).contains(String.class);
        assertThat(GenericType.resolveTypeArgument(Shipment.class, WithState.class, 1)).contains(Shipment.class);
        assertThat(GenericType.resolveTypeArgument(Shipment.class, WithState.class, 2)).contains(ShipmentState.class);
    }

    @Test
    void resolveTypeArgument_on_the_generic_superclass() {
        assertThat(GenericType.resolveTypeArgument(OrderState.class, AggregateState.class, 1)).contains(Order.class);
        assertThat(GenericType.resolveTypeArgument(SpecialOrder.class, AggregateRoot.class, 0)).contains(String.class);
    }

    @Test
    void resolveTypeArgument_is_empty_when_the_argument_stays_a_type_variable_or_the_type_is_unrelated() {
        assertThat(GenericType.resolveTypeArgument(GenericOrder.class, WithState.class, 0)).isEmpty();
        assertThat(GenericType.resolveTypeArgument(String.class, WithState.class, 0)).isEmpty();
    }

    @Test
    void resolveTypeArgument_rejects_an_index_the_generic_supertype_does_not_have() {
        assertThatThrownBy(() -> GenericType.resolveTypeArgument(Order.class, WithState.class, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --------------- Abstract classes that may optionally implement WithState -------------
    public static abstract class AggregateRoot<ID, AGGREGATE_TYPE extends AggregateRoot<ID, AGGREGATE_TYPE>> {

    }

    public static abstract class AggregateState<ID, AGGREGATE_TYPE extends AggregateRoot<ID, AGGREGATE_TYPE>> {

    }

    public interface WithState<ID, AGGREGATE_TYPE extends AggregateRoot<ID, AGGREGATE_TYPE>, AGGREGATE_STATE extends AggregateState<ID, AGGREGATE_TYPE>> {
    }

    // ---------------- Concrete classes ------------
    public static class Order extends AggregateRoot<String, Order> implements WithState<String, Order, OrderState> {

    }

    public static class OrderState extends AggregateState<String, Order> {

    }

    public static class SpecialOrder extends Order {

    }

    public static abstract class GenericOrder<ID, SELF extends GenericOrder<ID, SELF, STATE>, STATE extends AggregateState<ID, SELF>>
            extends AggregateRoot<ID, SELF> implements WithState<ID, SELF, STATE> {

    }

    public static class ProductOrder extends GenericOrder<Long, ProductOrder, ProductOrderState> {

    }

    public static class ProductOrderState extends AggregateState<Long, ProductOrder> {

    }

    public interface StateOf<STATE extends AggregateState<ID, AGGREGATE_TYPE>, AGGREGATE_TYPE extends AggregateRoot<ID, AGGREGATE_TYPE>, ID>
            extends WithState<ID, AGGREGATE_TYPE, STATE> {
    }

    public static class Shipment extends AggregateRoot<String, Shipment> implements StateOf<ShipmentState, Shipment, String> {

    }

    public static class ShipmentState extends AggregateState<String, Shipment> {

    }

}