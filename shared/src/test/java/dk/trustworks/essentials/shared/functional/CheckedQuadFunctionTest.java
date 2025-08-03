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

package dk.trustworks.essentials.shared.functional;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class CheckedQuadFunctionTest {

    @Test
    void test_the_CheckedQuadFunctions_apply_method_works_like_QuadFunctions_apply_method_when_no_exception_has_been_thrown() {
        var control = new Object() {
            boolean applyCalled = false;
            Integer argument1Value = null;
            BigDecimal argument2Value;
            Boolean argument3Value = null;
            String argument4Value = null;
        };

        var result = runTester(100, BigDecimal.ONE, true, "test", CheckedQuadFunction.safe((arg1, arg2, arg3, arg4) -> {
            control.applyCalled = true;
            control.argument1Value = arg1;
            control.argument2Value = arg2;
            control.argument3Value = arg3;
            control.argument4Value = arg4;
            return "result";
        }));

        assertThat(control.applyCalled).isTrue();
        assertThat(control.argument1Value).isEqualTo(100);
        assertThat(control.argument2Value).isEqualTo(BigDecimal.ONE);
        assertThat(control.argument3Value).isTrue();
        assertThat(control.argument4Value).isEqualTo("test");
        assertThat(result).isEqualTo("result");

    }

    @Test
    void test_the_CheckedQuadFunctions_apply_method_with_contextmessage_works_like_QuadFunctions_apply_method_when_no_exception_has_been_thrown() {
        var control = new Object() {
            boolean applyCalled = false;
            Integer argument1Value = null;
            BigDecimal argument2Value;
            Boolean argument3Value = null;
            String argument4Value = null;
        };

        var result = runTester(100, BigDecimal.ONE, true, "test", CheckedQuadFunction.safe("context-message",
                                                                                           (arg1, arg2, arg3, arg4) -> {
                                                                                               control.applyCalled = true;
                                                                                               control.argument1Value = arg1;
                                                                                               control.argument2Value = arg2;
                                                                                               control.argument3Value = arg3;
                                                                                               control.argument4Value = arg4;
                                                                                               return "result";
                                                                                           }));

        assertThat(control.applyCalled).isTrue();
        assertThat(control.argument1Value).isEqualTo(100);
        assertThat(control.argument2Value).isEqualTo(BigDecimal.ONE);
        assertThat(control.argument3Value).isTrue();
        assertThat(control.argument4Value).isEqualTo("test");
        assertThat(result).isEqualTo("result");
    }

    @Test
    void test_the_CheckedQuadFunctions_apply_method_catches_and_rethrows_a_checked_exception() {
        var control = new Object() {
            boolean applyCalled = false;
            Integer argument1Value = null;
            BigDecimal argument2Value;
            Boolean argument3Value = null;
            String argument4Value = null;
        };

        var rootCause = new IOException("Test");
        assertThatThrownBy(() ->
                                   runTester(100, BigDecimal.ONE, true, "test", CheckedQuadFunction.safe((arg1, arg2, arg3, arg4) -> {
                                       control.applyCalled = true;
                                       control.argument1Value = arg1;
                                       control.argument2Value = arg2;
                                       control.argument3Value = arg3;
                                       control.argument4Value = arg4;
                                       throw rootCause;
                                   }))
                          ).hasCause(rootCause);
        assertThat(control.applyCalled).isTrue();
        assertThat(control.argument1Value).isEqualTo(100);
        assertThat(control.argument2Value).isEqualTo(BigDecimal.ONE);
        assertThat(control.argument3Value).isTrue();
        assertThat(control.argument4Value).isEqualTo("test");
    }

    @Test
    void test_the_CheckedQuadFunctions_apply_method_with_contextmessage_catches_and_rethrows_a_checked_exception() {
        var control = new Object() {
            boolean applyCalled = false;
            Integer argument1Value = null;
            BigDecimal argument2Value;
            Boolean argument3Value = null;
            String argument4Value = null;
        };

        var rootCause = new IOException("Test");
        assertThatThrownBy(() ->
                                   runTester(100, BigDecimal.ONE, true, "test", CheckedQuadFunction.safe("context-message",
                                                                                                         (arg1, arg2, arg3, arg4) -> {
                                                                                                             control.applyCalled = true;
                                                                                                             control.argument1Value = arg1;
                                                                                                             control.argument2Value = arg2;
                                                                                                             control.argument3Value = arg3;
                                                                                                             control.argument4Value = arg4;
                                                                                                             throw rootCause;
                                                                                                         }))
                          ).isInstanceOf(CheckedExceptionRethrownException.class)
                           .hasMessage("context-message")
                           .hasRootCause(rootCause);
        assertThat(control.applyCalled).isTrue();
        assertThat(control.argument1Value).isEqualTo(100);
        assertThat(control.argument2Value).isEqualTo(BigDecimal.ONE);
        assertThat(control.argument3Value).isTrue();
        assertThat(control.argument4Value).isEqualTo("test");
    }

    @Test
    void test_the_CheckedQuadFunctions_apply_method_does_not_catch_a_runtime_exception() {
        var control = new Object() {
            boolean applyCalled = false;
            Integer argument1Value = null;
            BigDecimal argument2Value;
            Boolean argument3Value = null;
            String argument4Value = null;
        };

        var rootCause = new RuntimeException("Test");
        assertThatThrownBy(() ->
                                   runTester(100, BigDecimal.ONE, true, "test", CheckedQuadFunction.safe((arg1, arg2, arg3, arg4) -> {
                                       control.applyCalled = true;
                                       control.argument1Value = arg1;
                                       control.argument2Value = arg2;
                                       control.argument3Value = arg3;
                                       control.argument4Value = arg4;
                                       throw rootCause;
                                   }))
                          ).isEqualTo(rootCause);
        assertThat(control.applyCalled).isTrue();
        assertThat(control.argument1Value).isEqualTo(100);
        assertThat(control.argument2Value).isEqualTo(BigDecimal.ONE);
        assertThat(control.argument3Value).isTrue();
        assertThat(control.argument4Value).isEqualTo("test");
    }

    @Test
    void test_the_CheckedQuadFunctions_apply_method_with_contextmessage_does_not_catch_a_runtime_exception() {
        var control = new Object() {
            boolean applyCalled = false;
            Integer argument1Value = null;
            BigDecimal argument2Value;
            Boolean argument3Value = null;
            String argument4Value = null;
        };

        var rootCause = new RuntimeException("Test");
        assertThatThrownBy(() ->
                                   runTester(100, BigDecimal.ONE, true, "test", CheckedQuadFunction.safe("context-message",
                                                                                                         (arg1, arg2, arg3, arg4) -> {
                                                                                                             control.applyCalled = true;
                                                                                                             control.argument1Value = arg1;
                                                                                                             control.argument2Value = arg2;
                                                                                                             control.argument3Value = arg3;
                                                                                                             control.argument4Value = arg4;
                                                                                                             throw rootCause;
                                                                                                         }))
                          ).isEqualTo(rootCause);
        assertThat(control.applyCalled).isTrue();
        assertThat(control.argument1Value).isEqualTo(100);
        assertThat(control.argument2Value).isEqualTo(BigDecimal.ONE);
        assertThat(control.argument3Value).isTrue();
        assertThat(control.argument4Value).isEqualTo("test");
    }

    private static <T1, T2, T3, T4, R> R runTester(T1 t1, T2 t2, T3 t3, T4 t4, QuadFunction<T1, T2, T3, T4, R> function) {
        return function.apply(t1, t2, t3, t4);
    }

}