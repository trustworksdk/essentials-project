package com.example.shop.orders.use_cases.cancel_order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

/**
 * RULE: only methods carrying a JUnit 5 test annotation are listed; a {@code @Test} in this javadoc is not one.
 */
class CancelOrderDeciderTest {
    private static final String NOTE = "@Test void fromAString() {}";

    @Test
    void cancelsPlacedOrder() {
    }

    // @Test void commentedOut() {}

    /* @Test
       void blockCommented() {} */

    @DisplayName("rejects an unknown order")
    @Test
    void rejectsUnknownOrder() {
    }

    @org.junit.jupiter.api.Test
    void fullyQualifiedAnnotation() {
    }

    @ParameterizedTest
    @ValueSource(strings = {"o-1", "o-2"})
    void parameterised(String orderId) {
    }

    @RepeatedTest(3)
    void repeated() {
    }

    @TestFactory
    Stream<DynamicTest> factory() {
        return Stream.empty();
    }

    @TestTemplate
    void template() {
    }

    void helperIsNotATest() {
    }

    @Nested
    class WhenCancelled {
        @Test
        @DisplayName("cancelling twice is a " + "no-op")
        void secondCancelIsNoOp() {
        }

        @Nested
        class Twice {
            @Test
            void stillNoOp() {
            }
        }
    }
}
