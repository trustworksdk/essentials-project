/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.aggregates.decider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the {@link HandlerResult#fold(java.util.function.Function, java.util.function.Function)} method.
 * Tests verify that the fold method correctly applies the appropriate function based on whether 
 * the result is a success or error, following functional programming patterns.
 */
class HandlerResultTest {

    // Test data
    private static final String TEST_ERROR = "Test error message";
    private static final String TEST_EVENT_1 = "Event1";
    private static final String TEST_EVENT_2 = "Event2";
    private static final List<String> TEST_EVENTS = List.of(TEST_EVENT_1, TEST_EVENT_2);

    @Test
    void fold_should_apply_error_handler_when_result_is_error() {
        // Given
        var errorResult = HandlerResult.<String, String>error(TEST_ERROR);
        
        // When
        var result = errorResult.fold(
            error -> "Error handled: " + error,
            events -> "Success handled: " + events.size()
        );
        
        // Then
        assertThat(result).isEqualTo("Error handled: " + TEST_ERROR);
    }

    @Test
    void fold_should_apply_success_handler_when_result_is_success() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENTS);
        
        // When
        var result = successResult.fold(
            error -> "Error handled: " + error,
            events -> "Success handled: " + events.size()
        );
        
        // Then
        assertThat(result).isEqualTo("Success handled: " + TEST_EVENTS.size());
    }

    @Test
    void fold_should_apply_success_handler_when_result_is_success_with_empty_events() {
        // Given
        var successResult = HandlerResult.<String, String>events(List.of());
        
        // When
        var result = successResult.fold(
            error -> "Error handled: " + error,
            events -> "Success with " + events.size() + " events"
        );
        
        // Then
        assertThat(result).isEqualTo("Success with 0 events");
    }

    @Test
    void fold_should_apply_success_handler_when_result_is_success_with_single_event() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENT_1);
        
        // When
        var result = successResult.fold(
            error -> "Error: " + error,
            events -> "Events: " + String.join(", ", events)
        );
        
        // Then
        assertThat(result).isEqualTo("Events: " + TEST_EVENT_1);
    }

    @Test
    void fold_should_apply_success_handler_when_result_is_success_with_multiple_events() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENT_1, TEST_EVENT_2);
        
        // When
        var result = successResult.fold(
            error -> "Error: " + error,
            events -> "Events: " + String.join(", ", events)
        );
        
        // Then
        assertThat(result).isEqualTo("Events: " + TEST_EVENT_1 + ", " + TEST_EVENT_2);
    }

    @Test
    void fold_should_pass_actual_error_to_error_handler() {
        // Given
        var customError = "Custom error";
        var errorResult = HandlerResult.<String, String>error(customError);
        
        // When
        var result = errorResult.fold(
            error -> "Handled: " + error,
            events -> "Success"
        );
        
        // Then
        assertThat(result).isEqualTo("Handled: Custom error");
    }

    @Test
    void fold_should_pass_actual_events_list_to_success_handler() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENTS);
        
        // When
        var result = successResult.fold(
            error -> List.<String>of(),
            events -> events
        );
        
        // Then
        assertThat(result).isEqualTo(TEST_EVENTS);
        assertThat(result).containsExactly(TEST_EVENT_1, TEST_EVENT_2);
    }

    @Test
    void fold_should_throw_exception_when_error_handler_is_null() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENT_1);
        
        // When & Then
        assertThatThrownBy(() -> successResult.fold(null, events -> "Success"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("errorHandler cannot be null");
    }

    @Test
    void fold_should_throw_exception_when_success_handler_is_null() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENT_1);
        
        // When & Then
        assertThatThrownBy(() -> successResult.fold(error -> "Error", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("successHandler cannot be null");
    }

    @Test
    void fold_should_throw_exception_when_both_handlers_are_null() {
        // Given
        var successResult = HandlerResult.<String, String>events(TEST_EVENT_1);
        
        // When & Then
        assertThatThrownBy(() -> successResult.fold(null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("errorHandler cannot be null");
    }

    @Test
    void fold_should_work_with_different_return_types() {
        // Given
        var errorResult = HandlerResult.<String, String>error("Error");
        var successResult = HandlerResult.<String, String>events("Event");
        
        // When
        var errorResultAsInt = errorResult.fold(
            error -> error.length(),
            events -> events.size()
        );
        
        var successResultAsInt = successResult.fold(
            error -> error.length(),
            events -> events.size()
        );
        
        // Then
        assertThat(errorResultAsInt).isEqualTo(5); // "Error".length()
        assertThat(successResultAsInt).isEqualTo(1); // List with one event
    }

    @Test
    void fold_should_work_with_complex_transformations() {
        // Given
        var successResult = HandlerResult.<String, String>events("apple", "banana", "cherry");
        
        // When
        var totalLength = successResult.fold(
            error -> 0,
            events -> events.stream().mapToInt(String::length).sum()
        );
        
        // Then
        assertThat(totalLength).isEqualTo(17); // "apple"(5) + "banana"(6) + "cherry"(6)
    }

    @Test
    void fold_should_handle_exceptions_thrown_by_handlers() {
        // Given
        var errorResult = HandlerResult.<String, String>error("Error");
        
        // When & Then
        assertThatThrownBy(() -> errorResult.fold(
            error -> { throw new RuntimeException("Handler exception"); },
            events -> "Success"
        )).isInstanceOf(RuntimeException.class)
          .hasMessage("Handler exception");
    }
}