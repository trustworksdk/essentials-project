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

package dk.trustworks.essentials.shared.functional.tuple;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

class EitherTest {

    // --- Creation and Basic Operations ---

    @Test
    void verify_of_1_creates_Either_with_first_element() {
        Either<String, Integer> either = Either.of_1("error");

        assertThat(either.is_1()).isTrue();
        assertThat(either.is_2()).isFalse();
        assertThat(either._1()).isEqualTo("error");
        assertThat(either._2()).isNull();
    }

    @Test
    void verify_of_2_creates_Either_with_second_element() {
        Either<String, Integer> either = Either.of_2(42);

        assertThat(either.is_1()).isFalse();
        assertThat(either.is_2()).isTrue();
        assertThat(either._1()).isNull();
        assertThat(either._2()).isEqualTo(42);
    }

    @Test
    void verify_of_1_rejects_null() {
        assertThatThrownBy(() -> Either.of_1(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_of_2_rejects_null() {
        assertThatThrownBy(() -> Either.of_2(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_constructor_rejects_both_null() {
        assertThatThrownBy(() -> new Either<>(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("One element MUST have a non-null value");
    }

    @Test
    void verify_constructor_rejects_both_non_null() {
        assertThatThrownBy(() -> new Either<>("value1", "value2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one element can have a non-null value");
    }

    // --- Conditional Execution ---

    @Test
    void verify_ifIs_1_executes_consumer_when_is_1() {
        Either<String, Integer> either = Either.of_1("error");
        AtomicReference<String> captured = new AtomicReference<>();

        either.ifIs_1(captured::set);

        assertThat(captured.get()).isEqualTo("error");
    }

    @Test
    void verify_ifIs_1_does_not_execute_consumer_when_is_2() {
        Either<String, Integer> either = Either.of_2(42);
        AtomicBoolean executed = new AtomicBoolean(false);

        either.ifIs_1(error -> executed.set(true));

        assertThat(executed.get()).isFalse();
    }

    @Test
    void verify_ifIs_2_executes_consumer_when_is_2() {
        Either<String, Integer> either = Either.of_2(42);
        AtomicReference<Integer> captured = new AtomicReference<>();

        either.ifIs_2(captured::set);

        assertThat(captured.get()).isEqualTo(42);
    }

    @Test
    void verify_ifIs_2_does_not_execute_consumer_when_is_1() {
        Either<String, Integer> either = Either.of_1("error");
        AtomicBoolean executed = new AtomicBoolean(false);

        either.ifIs_2(value -> executed.set(true));

        assertThat(executed.get()).isFalse();
    }

    // --- Mapping ---

    @Test
    void verify_map2_transforms_second_element() {
        Either<String, Integer> either = Either.of_2(42);

        Either<String, Double> mapped = either.map2(v -> v * 2.0);

        assertThat(mapped.is_2()).isTrue();
        assertThat(mapped._2()).isEqualTo(84.0);
    }

    @Test
    void verify_map2_preserves_first_element_when_is_1() {
        Either<String, Integer> either = Either.of_1("error");

        // map2 applies the function even when is_1(), so use null-safe lambda
        Either<String, Double> mapped = either.map2(v -> v == null ? null : v * 2.0);

        assertThat(mapped.is_1()).isTrue();
        assertThat(mapped._1()).isEqualTo("error");
    }

    @Test
    void verify_map1_transforms_first_element() {
        Either<String, Integer> either = Either.of_1("error");

        Either<String, Integer> mapped = either.map1(String::toUpperCase);

        assertThat(mapped.is_1()).isTrue();
        assertThat(mapped._1()).isEqualTo("ERROR");
    }

    @Test
    void verify_map1_preserves_second_element_when_is_2() {
        Either<String, Integer> either = Either.of_2(42);

        // map1 applies the function even when is_2(), so use null-safe lambda
        Either<String, Integer> mapped = either.map1(e -> e == null ? null : e.toUpperCase());

        assertThat(mapped.is_2()).isTrue();
        assertThat(mapped._2()).isEqualTo(42);
    }

    // --- Fold (Pattern Matching Style) ---

    @Test
    void verify_fold_applies_first_function_when_is_1() {
        Either<String, Integer> either = Either.of_1("error");

        String result = either.fold(
                error -> "Error: " + error,
                value -> "Value: " + value
        );

        assertThat(result).isEqualTo("Error: error");
    }

    @Test
    void verify_fold_applies_second_function_when_is_2() {
        Either<String, Integer> either = Either.of_2(42);

        String result = either.fold(
                error -> "Error: " + error,
                value -> "Value: " + value
        );

        assertThat(result).isEqualTo("Value: 42");
    }

    @Test
    void verify_fold_with_different_return_types() {
        Either<String, Integer> success = Either.of_2(100);
        Either<String, Integer> failure = Either.of_1("not found");

        int successResult = success.fold(
                error -> -1,
                value -> value
        );
        int failureResult = failure.fold(
                error -> -1,
                value -> value
        );

        assertThat(successResult).isEqualTo(100);
        assertThat(failureResult).isEqualTo(-1);
    }

    @Test
    void verify_fold_rejects_null_ifIs1_function() {
        Either<String, Integer> either = Either.of_2(42);

        assertThatThrownBy(() -> either.fold(null, Object::toString))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ifIs1");
    }

    @Test
    void verify_fold_rejects_null_ifIs2_function() {
        Either<String, Integer> either = Either.of_2(42);

        assertThatThrownBy(() -> either.fold(e -> e, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ifIs2");
    }

    // --- FlatMap (Chaining) ---

    @Test
    void verify_flatMap2_chains_successful_operations() {
        Either<String, Integer> initial = Either.of_2(10);

        Either<String, Integer> result = initial
                .flatMap2(v -> Either.of_2(v * 2))
                .flatMap2(v -> Either.of_2(v + 5));

        assertThat(result.is_2()).isTrue();
        assertThat(result._2()).isEqualTo(25);
    }

    @Test
    void verify_flatMap2_short_circuits_on_first_failure() {
        Either<String, Integer> initial = Either.of_2(10);
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        Either<String, Integer> result = initial
                .flatMap2(v -> Either.<String, Integer>of_1("validation failed"))
                .flatMap2(v -> {
                    secondCalled.set(true);
                    return Either.of_2(v * 2);
                });

        assertThat(result.is_1()).isTrue();
        assertThat(result._1()).isEqualTo("validation failed");
        assertThat(secondCalled.get()).isFalse();
    }

    @Test
    void verify_flatMap2_propagates_initial_failure() {
        Either<String, Integer> initial = Either.of_1("initial error");
        AtomicBoolean mapperCalled = new AtomicBoolean(false);

        Either<String, Integer> result = initial
                .flatMap2(v -> {
                    mapperCalled.set(true);
                    return Either.of_2(v * 2);
                });

        assertThat(result.is_1()).isTrue();
        assertThat(result._1()).isEqualTo("initial error");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void verify_flatMap2_rejects_null_mapping_function() {
        Either<String, Integer> either = Either.of_2(42);

        assertThatThrownBy(() -> either.flatMap2(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping function");
    }

    @Test
    void verify_flatMap1_chains_on_first_element() {
        Either<String, Integer> initial = Either.of_1("error");

        Either<String, Integer> result = initial
                .flatMap1(e -> Either.of_1(e.toUpperCase()));

        assertThat(result.is_1()).isTrue();
        assertThat(result._1()).isEqualTo("ERROR");
    }

    @Test
    void verify_flatMap1_short_circuits_when_is_2() {
        Either<String, Integer> initial = Either.of_2(42);
        AtomicBoolean mapperCalled = new AtomicBoolean(false);

        Either<String, Integer> result = initial
                .flatMap1(e -> {
                    mapperCalled.set(true);
                    return Either.of_1(e.toUpperCase());
                });

        assertThat(result.is_2()).isTrue();
        assertThat(result._2()).isEqualTo(42);
        assertThat(mapperCalled.get()).isFalse();
    }

    // --- Integration Scenario: Validation Pipeline ---

    @Test
    void verify_validation_pipeline_with_flatMap2_and_fold() {
        // Track method invocations
        AtomicBoolean enrichOrderCalled = new AtomicBoolean(false);
        AtomicBoolean persistOrderCalled = new AtomicBoolean(false);

        // Simulate a validation pipeline
        Either<String, Order> result = validateOrder(new OrderData("ORD-123", 100))
                .flatMap2(order -> {
                    enrichOrderCalled.set(true);
                    return enrichOrder(order);
                })
                .flatMap2(order -> {
                    persistOrderCalled.set(true);
                    return persistOrder(order);
                });

        String message = result.fold(
                error -> "Failed: " + error,
                order -> "Success: " + order.id()
        );

        assertThat(message).isEqualTo("Success: ORD-123");
        assertThat(enrichOrderCalled.get()).as("enrichOrder should be called").isTrue();
        assertThat(persistOrderCalled.get()).as("persistOrder should be called").isTrue();
        assertThat(result._2().enriched()).as("Order should be enriched").isTrue();
    }

    @Test
    void verify_validation_pipeline_fails_on_invalid_data() {
        // Track method invocations to verify short-circuit behavior
        AtomicBoolean enrichOrderCalled = new AtomicBoolean(false);
        AtomicBoolean persistOrderCalled = new AtomicBoolean(false);

        Either<String, Order> result = validateOrder(new OrderData(null, 100))
                .flatMap2(order -> {
                    enrichOrderCalled.set(true);
                    return enrichOrder(order);
                })
                .flatMap2(order -> {
                    persistOrderCalled.set(true);
                    return persistOrder(order);
                });

        String message = result.fold(
                error -> "Failed: " + error,
                order -> "Success: " + order.id()
        );

        assertThat(message).isEqualTo("Failed: Order ID is required");
        assertThat(enrichOrderCalled.get()).as("enrichOrder should NOT be called on validation failure").isFalse();
        assertThat(persistOrderCalled.get()).as("persistOrder should NOT be called on validation failure").isFalse();
    }

    @Test
    void verify_validation_pipeline_fails_on_negative_amount() {
        // Track method invocations to verify short-circuit behavior
        AtomicBoolean enrichOrderCalled = new AtomicBoolean(false);
        AtomicBoolean persistOrderCalled = new AtomicBoolean(false);

        Either<String, Order> result = validateOrder(new OrderData("ORD-456", -50))
                .flatMap2(order -> {
                    enrichOrderCalled.set(true);
                    return enrichOrder(order);
                })
                .flatMap2(order -> {
                    persistOrderCalled.set(true);
                    return persistOrder(order);
                });

        String message = result.fold(
                error -> "Failed: " + error,
                order -> "Success: " + order.id()
        );

        assertThat(message).isEqualTo("Failed: Amount must be positive");
        assertThat(enrichOrderCalled.get()).as("enrichOrder should NOT be called on validation failure").isFalse();
        assertThat(persistOrderCalled.get()).as("persistOrder should NOT be called on validation failure").isFalse();
    }

    // --- Equals, HashCode, ToString ---

    @Test
    void verify_equals_and_hashCode() {
        Either<String, Integer> either1 = Either.of_2(42);
        Either<String, Integer> either2 = Either.of_2(42);
        Either<String, Integer> either3 = Either.of_2(100);
        Either<String, Integer> either4 = Either.of_1("error");
        Either<String, Integer> either5 = Either.of_1("error");

        assertThat(either1).isEqualTo(either1);
        assertThat(either1).isEqualTo(either2);
        assertThat(either1).isNotEqualTo(either3);
        assertThat(either1).isNotEqualTo(either4);
        assertThat(either4).isEqualTo(either5);

        assertThat(either1.hashCode()).isEqualTo(either2.hashCode());
        assertThat(either4.hashCode()).isEqualTo(either5.hashCode());
    }

    @Test
    void verify_swap() {
        Either<String, Integer> either = Either.of_2(42);

        Either<Integer, String> swapped = either.swap();

        assertThat(swapped.is_1()).isTrue();
        assertThat(swapped._1()).isEqualTo(42);
    }

    @Test
    void verify_get_1_and_get_2_optionals() {
        Either<String, Integer> success = Either.of_2(42);
        Either<String, Integer> failure = Either.of_1("error");

        assertThat(success.get_1()).isEmpty();
        assertThat(success.get_2()).hasValue(42);
        assertThat(failure.get_1()).hasValue("error");
        assertThat(failure.get_2()).isEmpty();
    }

    @Test
    void verify_arity() {
        Either<String, Integer> either = Either.of_2(42);

        assertThat(either.arity()).isEqualTo(1);
    }

    @Test
    void verify_toList() {
        Either<String, Integer> success = Either.of_2(42);
        Either<String, Integer> failure = Either.of_1("error");

        assertThat(success.toList()).hasSize(1).first().isEqualTo(42);
        assertThat(failure.toList()).hasSize(1).first().isEqualTo("error");
    }

    @Test
    void verify_toResult_converts_Either_to_Result() {
        Either<String, Integer> eitherSuccess = Either.of_2(42);
        Either<String, Integer> eitherError = Either.of_1("error");

        Result<String, Integer> resultSuccess = eitherSuccess.toResult();
        Result<String, Integer> resultError = eitherError.toResult();

        assertThat(resultSuccess.isSuccess()).isTrue();
        assertThat(resultSuccess.success()).isEqualTo(42);
        assertThat(resultError.isError()).isTrue();
        assertThat(resultError.error()).isEqualTo("error");
    }

    // --- Helper types and methods for validation pipeline tests ---

    private record OrderData(String id, int amount) {}
    private record Order(String id, int amount, boolean enriched) {}

    private Either<String, Order> validateOrder(OrderData data) {
        if (data.id() == null || data.id().isBlank()) {
            return Either.of_1("Order ID is required");
        }
        if (data.amount() <= 0) {
            return Either.of_1("Amount must be positive");
        }
        return Either.of_2(new Order(data.id(), data.amount(), false));
    }

    private Either<String, Order> enrichOrder(Order order) {
        return Either.of_2(new Order(order.id(), order.amount(), true));
    }

    private Either<String, Order> persistOrder(Order order) {
        // Simulate successful persistence
        return Either.of_2(order);
    }
}
