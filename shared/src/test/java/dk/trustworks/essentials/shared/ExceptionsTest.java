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

package dk.trustworks.essentials.shared;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

public class ExceptionsTest {

    @Test
    void shouldReturnTrue_whenExceptionMatchesType() {
        var exception = new RuntimeException("Test exception");
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, RuntimeException.class)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenCauseMatchesType() {
        var cause = new IllegalArgumentException("Cause exception");
        var exception = new RuntimeException("Wrapper exception", cause);
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, IllegalArgumentException.class)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenRootCauseMatchesType() {
        var rootCause = new IllegalStateException("Nested cause");
        var nestedCause = new RuntimeException("Cause exception", rootCause);
        var exception = new RuntimeException("Wrapper exception", nestedCause);
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, IllegalStateException.class)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenNestedCauseMatchesType() {
        var rootCause = new RuntimeException("Cause exception");
        var nestedCause = new IllegalStateException("Nested cause", rootCause);
        var exception = new RuntimeException("Wrapper exception", nestedCause);
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, IllegalStateException.class)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenTopLevelExceptionMatchesType() {
        var rootCause = new NullPointerException("Cause exception");
        var nestedCause = new IllegalStateException("Nested cause", rootCause);
        var exception = new SQLException("Wrapper exception", nestedCause);
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, SQLException.class)).isTrue();
    }

    @Test
    void shouldReturnFalse_whenTypeDoesNotMatch() {
        var exception = new RuntimeException("Test exception");
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, IllegalArgumentException.class)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenExceptionChainDoesNotMatchType() {
        var cause = new IllegalArgumentException("Cause exception");
        var exception = new RuntimeException("Wrapper exception", cause);
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, IllegalStateException.class)).isFalse();
    }

    @Test
    void shouldThrowIllegalArgumentExceptionForNullInputs() {
        assertThatThrownBy(() -> Exceptions.doesStackTraceContainExceptionOfType(new RuntimeException("Test exception"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You must supply an exception type");

        assertThatThrownBy(() -> Exceptions.doesStackTraceContainExceptionOfType(null, RuntimeException.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You must supply an exception");
    }

    @Test
    void shouldHandleCircularReferenceInExceptionChain() {
        var exception = new RuntimeException("Outer exception");
        var innerException = new RuntimeException("Inner exception");
        exception.initCause(innerException);
        innerException.initCause(exception); // Create circular reference

        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, RuntimeException.class)).isTrue();
        assertThat(Exceptions.doesStackTraceContainExceptionOfType(exception, IllegalArgumentException.class)).isFalse();
    }

    @Test
    void shouldSneakyThrowCheckedException() {
        assertThatThrownBy(() -> Exceptions.sneakyThrow(new Exception("Checked exception")))
                .isInstanceOf(Exception.class)
                .hasMessage("Checked exception");
    }

    @Test
    void shouldSneakyThrowRuntimeException() {
        assertThatThrownBy(() -> Exceptions.sneakyThrow(new IllegalStateException("IllegalStateException msg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IllegalStateException msg");
    }

    @Test
    void shouldSneakyThrowIllegalArgumentExceptionWhenNullSupplied() {
        assertThatThrownBy(() -> Exceptions.sneakyThrow(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You must supply an exception");
    }

    @Test
    void shouldReturnStackTraceAsString() {
        var exception = new RuntimeException("Simulated exception");
        var stackTrace = Exceptions.getStackTrace(exception);

        assertThat(stackTrace).contains("RuntimeException: Simulated exception");
        assertThat(stackTrace).contains("at"); // Validate some stack trace content
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenGetStackTraceCalledWithNull() {
        assertThatThrownBy(() -> Exceptions.getStackTrace(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You must specify a throwable");
    }

    @Test
    void shouldReturnRootCause() {
        var rootCause = new IllegalArgumentException("Root cause exception");
        var middleException = new RuntimeException("Middle exception", rootCause);
        var topException = new Exception("Top-level exception", middleException);

        assertThat(Exceptions.getRootCause(topException)).isSameAs(rootCause);
    }

    @Test
    void shouldReturnRootCauseWhenExceptionHasNoCause() {
        var exception = new RuntimeException("Standalone exception");
        assertThat(Exceptions.getRootCause(exception)).isSameAs(exception);
    }

    @Test
    void shouldHandleCircularReferencesInGetRootCause() {
        var topException = new RuntimeException("Top-level exception");
        var middleException = new RuntimeException("Middle exception", topException);
        topException.initCause(middleException);

        assertThat(Exceptions.getRootCause(topException)).isSameAs(middleException);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenGetRootCauseCalledWithNull() {
        assertThatThrownBy(() -> Exceptions.getRootCause(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You must supply an exception");
    }

    @Test
    void prependRootCauseSummary_returnsTheDescriptionUnchanged_whenTheThrowableIsNotWrapped() {
        var exception = new IllegalStateException("boom");

        assertThat(Exceptions.prependRootCauseSummary(exception, "the description")).isEqualTo("the description");
    }

    @Test
    void prependRootCauseSummary_leadsWithTheRootCause_whenTheThrowableIsWrappedOnce() {
        var exception = new RuntimeException("wrapper", new IllegalStateException("the real reason"));

        assertThat(Exceptions.prependRootCauseSummary(exception, "the description"))
                .isEqualTo(Exceptions.ROOT_CAUSE_PREFIX + "java.lang.IllegalStateException: the real reason" + System.lineSeparator()
                                   + "the description");
    }

    @Test
    void prependRootCauseSummary_namesTheInnermostCause_whenTheThrowableIsWrappedSeveralTimes() {
        var exception = new RuntimeException("outer",
                                             new IllegalArgumentException("middle",
                                                                          new IllegalStateException("the real reason")));

        assertThat(Exceptions.prependRootCauseSummary(exception, "the description"))
                .isEqualTo("Root cause: java.lang.IllegalStateException: the real reason" + System.lineSeparator()
                                   + "the description");
    }

    @Test
    void prependRootCauseSummary_rendersOnlyTheType_whenTheRootCauseHasNoMessage() {
        var exception = new RuntimeException("wrapper", new IllegalStateException());

        assertThat(Exceptions.prependRootCauseSummary(exception, "the description"))
                .isEqualTo("Root cause: java.lang.IllegalStateException" + System.lineSeparator() + "the description");
    }

    @Test
    void prependRootCauseSummary_keepsAMultiLineRootCauseMessageVerbatim() {
        var rootCauseMessage = "ERROR: duplicate key value" + System.lineSeparator() + "  Detail: Key (id)=(1) already exists.";
        var exception        = new RuntimeException("wrapper", new IllegalStateException(rootCauseMessage));

        assertThat(Exceptions.prependRootCauseSummary(exception, "the description"))
                .isEqualTo("Root cause: java.lang.IllegalStateException: " + rootCauseMessage + System.lineSeparator() + "the description");
    }

    @Test
    void prependRootCauseSummary_doesNotLoop_onACauseCycle() {
        var outer = new RuntimeException("outer");
        var inner = new IllegalStateException("inner", outer);
        outer.initCause(inner);

        assertThat(Exceptions.prependRootCauseSummary(outer, "the description"))
                .isEqualTo("Root cause: java.lang.IllegalStateException: inner" + System.lineSeparator() + "the description");
    }

    @Test
    void prependRootCauseSummary_rejectsMissingArguments() {
        assertThatThrownBy(() -> Exceptions.prependRootCauseSummary(null, "the description"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Exceptions.prependRootCauseSummary(new RuntimeException(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
