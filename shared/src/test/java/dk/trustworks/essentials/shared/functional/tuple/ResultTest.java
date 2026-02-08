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

class ResultTest {

    // ==================== Creation ====================

    @Test
    void verify_success_creates_Result_with_success_value() {
        Result<String, Integer> result = Result.success(42);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(result.success()).isEqualTo(42);
        assertThat(result.error()).isNull();
    }

    @Test
    void verify_error_creates_Result_with_error_value() {
        Result<String, Integer> result = Result.error("Something went wrong");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isError()).isTrue();
        assertThat(result.success()).isNull();
        assertThat(result.error()).isEqualTo("Something went wrong");
    }

    @Test
    void verify_success_rejects_null() {
        assertThatThrownBy(() -> Result.success(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("success value");
    }

    @Test
    void verify_error_rejects_null() {
        assertThatThrownBy(() -> Result.error(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error value");
    }

    @Test
    void verify_fromEither_converts_Either_to_Result() {
        Either<String, Integer> eitherSuccess = Either.of_2(42);
        Either<String, Integer> eitherError = Either.of_1("error");

        Result<String, Integer> resultSuccess = Result.fromEither(eitherSuccess);
        Result<String, Integer> resultError = Result.fromEither(eitherError);

        assertThat(resultSuccess.isSuccess()).isTrue();
        assertThat(resultSuccess.success()).isEqualTo(42);
        assertThat(resultError.isError()).isTrue();
        assertThat(resultError.error()).isEqualTo("error");
    }

    @Test
    void verify_fromEither_rejects_null() {
        assertThatThrownBy(() -> Result.fromEither(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== Optional Accessors ====================

    @Test
    void verify_getSuccess_returns_Optional() {
        Result<String, Integer> success = Result.success(42);
        Result<String, Integer> error = Result.error("error");

        assertThat(success.getSuccess()).hasValue(42);
        assertThat(success.getError()).isEmpty();
        assertThat(error.getSuccess()).isEmpty();
        assertThat(error.getError()).hasValue("error");
    }

    // ==================== Conditional Execution ====================

    @Test
    void verify_ifSuccess_executes_consumer_when_success() {
        Result<String, Integer> result = Result.success(42);
        AtomicReference<Integer> captured = new AtomicReference<>();

        result.ifSuccess(captured::set);

        assertThat(captured.get()).isEqualTo(42);
    }

    @Test
    void verify_ifSuccess_does_not_execute_consumer_when_error() {
        Result<String, Integer> result = Result.error("error");
        AtomicBoolean executed = new AtomicBoolean(false);

        result.ifSuccess(value -> executed.set(true));

        assertThat(executed.get()).isFalse();
    }

    @Test
    void verify_ifError_executes_consumer_when_error() {
        Result<String, Integer> result = Result.error("error");
        AtomicReference<String> captured = new AtomicReference<>();

        result.ifError(captured::set);

        assertThat(captured.get()).isEqualTo("error");
    }

    @Test
    void verify_ifError_does_not_execute_consumer_when_success() {
        Result<String, Integer> result = Result.success(42);
        AtomicBoolean executed = new AtomicBoolean(false);

        result.ifError(error -> executed.set(true));

        assertThat(executed.get()).isFalse();
    }

    // ==================== Mapping ====================

    @Test
    void verify_mapSuccess_transforms_success_value() {
        Result<String, Integer> result = Result.success(42);

        Result<String, String> mapped = result.mapSuccess(v -> "Value: " + v);

        assertThat(mapped.isSuccess()).isTrue();
        assertThat(mapped.success()).isEqualTo("Value: 42");
    }

    @Test
    void verify_mapSuccess_preserves_error() {
        Result<String, Integer> result = Result.error("error");

        Result<String, String> mapped = result.mapSuccess(v -> "Value: " + v);

        assertThat(mapped.isError()).isTrue();
        assertThat(mapped.error()).isEqualTo("error");
    }

    @Test
    void verify_mapSuccess_rejects_null_mapper() {
        Result<String, Integer> result = Result.success(42);

        assertThatThrownBy(() -> result.mapSuccess(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapper");
    }

    @Test
    void verify_mapError_transforms_error_value() {
        Result<String, Integer> result = Result.error("error");

        Result<String, Integer> mapped = result.mapError(String::toUpperCase);

        assertThat(mapped.isError()).isTrue();
        assertThat(mapped.error()).isEqualTo("ERROR");
    }

    @Test
    void verify_mapError_preserves_success() {
        Result<String, Integer> result = Result.success(42);

        Result<String, Integer> mapped = result.mapError(String::toUpperCase);

        assertThat(mapped.isSuccess()).isTrue();
        assertThat(mapped.success()).isEqualTo(42);
    }

    @Test
    void verify_mapError_rejects_null_mapper() {
        Result<String, Integer> result = Result.error("error");

        assertThatThrownBy(() -> result.mapError(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapper");
    }

    // ==================== FlatMapping ====================

    @Test
    void verify_flatMapSuccess_chains_successful_operations() {
        Result<String, Integer> initial = Result.success(10);

        Result<String, Integer> result = initial
                .flatMapSuccess(v -> Result.success(v * 2))
                .flatMapSuccess(v -> Result.success(v + 5));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success()).isEqualTo(25);
    }

    @Test
    void verify_flatMapSuccess_short_circuits_on_error() {
        Result<String, Integer> initial = Result.success(10);
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        Result<String, Integer> result = initial
                .flatMapSuccess(v -> Result.<String, Integer>error("validation failed"))
                .flatMapSuccess(v -> {
                    secondCalled.set(true);
                    return Result.success(v * 2);
                });

        assertThat(result.isError()).isTrue();
        assertThat(result.error()).isEqualTo("validation failed");
        assertThat(secondCalled.get()).isFalse();
    }

    @Test
    void verify_flatMapSuccess_propagates_initial_error() {
        Result<String, Integer> initial = Result.error("initial error");
        AtomicBoolean mapperCalled = new AtomicBoolean(false);

        Result<String, Integer> result = initial
                .flatMapSuccess(v -> {
                    mapperCalled.set(true);
                    return Result.success(v * 2);
                });

        assertThat(result.isError()).isTrue();
        assertThat(result.error()).isEqualTo("initial error");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void verify_flatMapSuccess_rejects_null_mapper() {
        Result<String, Integer> result = Result.success(42);

        assertThatThrownBy(() -> result.flatMapSuccess(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapper");
    }

    @Test
    void verify_flatMapError_chains_error_operations() {
        Result<String, Integer> initial = Result.error("error");

        Result<String, Integer> result = initial
                .flatMapError(e -> Result.error(e.toUpperCase()));

        assertThat(result.isError()).isTrue();
        assertThat(result.error()).isEqualTo("ERROR");
    }

    @Test
    void verify_flatMapError_preserves_success() {
        Result<String, Integer> initial = Result.success(42);
        AtomicBoolean mapperCalled = new AtomicBoolean(false);

        Result<String, Integer> result = initial
                .flatMapError(e -> {
                    mapperCalled.set(true);
                    return Result.error(e.toUpperCase());
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success()).isEqualTo(42);
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void verify_flatMapError_can_recover_to_success() {
        Result<String, Integer> initial = Result.error("error");

        Result<String, Integer> result = initial
                .flatMapError(e -> Result.success(0));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success()).isEqualTo(0);
    }

    // ==================== Fold ====================

    @Test
    void verify_fold_applies_error_function_when_error() {
        Result<String, Integer> result = Result.error("error");

        String message = result.fold(
                error -> "Error: " + error,
                value -> "Value: " + value
        );

        assertThat(message).isEqualTo("Error: error");
    }

    @Test
    void verify_fold_applies_success_function_when_success() {
        Result<String, Integer> result = Result.success(42);

        String message = result.fold(
                error -> "Error: " + error,
                value -> "Value: " + value
        );

        assertThat(message).isEqualTo("Value: 42");
    }

    // ==================== Swap ====================

    @Test
    void verify_swap_exchanges_success_and_error() {
        Result<String, Integer> success = Result.success(42);
        Result<String, Integer> error = Result.error("error");

        Result<Integer, String> swappedSuccess = success.swap();
        Result<Integer, String> swappedError = error.swap();

        assertThat(swappedSuccess.isError()).isTrue();
        assertThat(swappedSuccess.error()).isEqualTo(42);
        assertThat(swappedError.isSuccess()).isTrue();
        assertThat(swappedError.success()).isEqualTo("error");
    }

    // ==================== Conversion ====================

    @Test
    void verify_toEither_returns_this() {
        Result<String, Integer> result = Result.success(42);

        Either<String, Integer> either = result.toEither();

        assertThat(either).isSameAs(result);
        assertThat(either.is_2()).isTrue();
        assertThat(either._2()).isEqualTo(42);
    }

    // ==================== Either Compatibility ====================

    @Test
    void verify_Result_is_compatible_with_Either_methods() {
        Result<String, Integer> result = Result.success(42);

        // Either methods should work
        assertThat(result.is_2()).isTrue();
        assertThat(result._2()).isEqualTo(42);
        assertThat(result.get_2()).hasValue(42);
        assertThat(result.arity()).isEqualTo(1);
    }

    // ==================== toString ====================

    @Test
    void verify_toString_for_success() {
        Result<String, Integer> result = Result.success(42);

        assertThat(result.toString()).isEqualTo("Result.success(42)");
    }

    @Test
    void verify_toString_for_error() {
        Result<String, Integer> result = Result.error("something went wrong");

        assertThat(result.toString()).isEqualTo("Result.error(something went wrong)");
    }

    // ==================== Equals and HashCode ====================

    @Test
    void verify_equals_and_hashCode() {
        Result<String, Integer> success1 = Result.success(42);
        Result<String, Integer> success2 = Result.success(42);
        Result<String, Integer> success3 = Result.success(100);
        Result<String, Integer> error1 = Result.error("error");
        Result<String, Integer> error2 = Result.error("error");

        assertThat(success1).isEqualTo(success2);
        assertThat(success1).isNotEqualTo(success3);
        assertThat(success1).isNotEqualTo(error1);
        assertThat(error1).isEqualTo(error2);

        assertThat(success1.hashCode()).isEqualTo(success2.hashCode());
        assertThat(error1.hashCode()).isEqualTo(error2.hashCode());
    }

    @Test
    void verify_Result_equals_Either_with_same_values() {
        Result<String, Integer> result = Result.success(42);
        Either<String, Integer> either = Either.of_2(42);

        // Result and Either with same values should be equal (since Result extends Either)
        assertThat(result).isEqualTo(either);
        assertThat(either).isEqualTo(result);
    }

    // ==================== Integration Scenario ====================

    @Test
    void verify_validation_pipeline() {
        AtomicBoolean enrichCalled = new AtomicBoolean(false);
        AtomicBoolean persistCalled = new AtomicBoolean(false);

        Result<ValidationError, Order> result = validateOrder(new OrderData("ORD-123", 100))
                .flatMapSuccess(order -> {
                    enrichCalled.set(true);
                    return enrichOrder(order);
                })
                .flatMapSuccess(order -> {
                    persistCalled.set(true);
                    return persistOrder(order);
                });

        String message = result.fold(
                error -> "Failed: " + error.message(),
                order -> "Success: " + order.id()
        );

        assertThat(message).isEqualTo("Success: ORD-123");
        assertThat(enrichCalled.get()).as("enrichOrder should be called").isTrue();
        assertThat(persistCalled.get()).as("persistOrder should be called").isTrue();
        assertThat(result.success().enriched()).as("Order should be enriched").isTrue();
    }

    @Test
    void verify_validation_pipeline_fails_and_short_circuits() {
        AtomicBoolean enrichCalled = new AtomicBoolean(false);
        AtomicBoolean persistCalled = new AtomicBoolean(false);

        Result<ValidationError, Order> result = validateOrder(new OrderData(null, 100))
                .flatMapSuccess(order -> {
                    enrichCalled.set(true);
                    return enrichOrder(order);
                })
                .flatMapSuccess(order -> {
                    persistCalled.set(true);
                    return persistOrder(order);
                });

        String message = result.fold(
                error -> "Failed: " + error.message(),
                order -> "Success: " + order.id()
        );

        assertThat(message).isEqualTo("Failed: Order ID is required");
        assertThat(enrichCalled.get()).as("enrichOrder should NOT be called").isFalse();
        assertThat(persistCalled.get()).as("persistOrder should NOT be called").isFalse();
    }

    @Test
    void verify_error_recovery_with_flatMapError() {
        Result<ValidationError, Order> result = validateOrder(new OrderData(null, 100))
                .flatMapError(error -> {
                    // Recover by creating a default order
                    return Result.success(new Order("DEFAULT-001", 0, false));
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success().id()).isEqualTo("DEFAULT-001");
    }

    // ==================== Helper Types ====================

    private record ValidationError(String message) {}
    private record OrderData(String id, int amount) {}
    private record Order(String id, int amount, boolean enriched) {}

    private Result<ValidationError, Order> validateOrder(OrderData data) {
        if (data.id() == null || data.id().isBlank()) {
            return Result.error(new ValidationError("Order ID is required"));
        }
        if (data.amount() <= 0) {
            return Result.error(new ValidationError("Amount must be positive"));
        }
        return Result.success(new Order(data.id(), data.amount(), false));
    }

    private Result<ValidationError, Order> enrichOrder(Order order) {
        return Result.success(new Order(order.id(), order.amount(), true));
    }

    private Result<ValidationError, Order> persistOrder(Order order) {
        return Result.success(order);
    }
}
