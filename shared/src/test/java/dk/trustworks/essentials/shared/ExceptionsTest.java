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
import java.util.*;

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

    @Test
    void forEachThenThrowFirst_runsTheActionForEveryItem_whenNoneThrows() {
        var ran = new ArrayList<String>();

        Exceptions.forEachThenThrowFirst(List.of("a", "b", "c"), ran::add);

        assertThat(ran).containsExactly("a", "b", "c");
    }

    @Test
    void forEachThenThrowFirst_runsEveryItem_andThrowsTheFirstFailure_withTheOthersSuppressed() {
        var ran    = new ArrayList<String>();
        var first  = new IllegalStateException("a");
        var second = new AssertionError("c");

        var thrown = catchThrowable(() -> Exceptions.forEachThenThrowFirst(List.of("a", "b", "c"), item -> {
            ran.add(item);
            switch (item) {
                case "a" -> throw first;
                case "c" -> throw second;
                default -> { }
            }
        }));

        assertThat(ran).as("every item ran, also after a throw").containsExactly("a", "b", "c");
        assertThat(thrown).isSameAs(first);
        assertThat(thrown.getSuppressed()).containsExactly(second);
    }

    @Test
    void forEachThenThrowFirst_throwsANonCriticalErrorAsItWasThrown() {
        var error = new AssertionError("the only failure");

        assertThatThrownBy(() -> Exceptions.forEachThenThrowFirst(List.of("a", "b"), item -> {
            if ("a".equals(item)) {
                throw error;
            }
        })).isSameAs(error);
    }

    @Test
    void forEachThenThrowFirst_doesNotSuppressAFailureInItself_whenEveryItemThrowsTheSameInstance() {
        var ran     = new ArrayList<String>();
        var failure = new IllegalStateException("shared instance");

        var thrown = catchThrowable(() -> Exceptions.forEachThenThrowFirst(List.of("a", "b", "c"), item -> {
            ran.add(item);
            throw failure;
        }));

        assertThat(ran).containsExactly("a", "b", "c");
        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    @Test
    void forEachThenThrowFirst_suppressesALaterSharedInstanceOnce_whenSeveralItemsThrowIt() {
        var ran    = new ArrayList<String>();
        var first  = new IllegalStateException("a");
        var shared = new IllegalStateException("shared by b, c and d");

        var thrown = catchThrowable(() -> Exceptions.forEachThenThrowFirst(List.of("a", "b", "c", "d"), item -> {
            ran.add(item);
            if ("a".equals(item)) {
                throw first;
            }
            throw shared;
        }));

        assertThat(ran).containsExactly("a", "b", "c", "d");
        assertThat(thrown).isSameAs(first);
        assertThat(thrown.getSuppressed()).containsExactly(shared);
    }

    @Test
    void forEachThenThrowFirst_rethrowsACriticalErrorAtOnce_withTheFailureSoFarSuppressed() {
        var ran      = new ArrayList<String>();
        var first    = new IllegalStateException("a");
        var critical = new StackOverflowError("b");

        var thrown = catchThrowable(() -> Exceptions.forEachThenThrowFirst(List.of("a", "b", "c"), item -> {
            ran.add(item);
            switch (item) {
                case "a" -> throw first;
                case "b" -> throw critical;
                default -> { }
            }
        }));

        assertThat(thrown).isSameAs(critical);
        assertThat(thrown.getSuppressed()).containsExactly(first);
        assertThat(ran).as("no item ran after the critical error").containsExactly("a", "b");
    }

    @Test
    void forEachCollectingFailures_addsEveryFailureToTheGivenOne_andReturnsIt() {
        var ran     = new ArrayList<String>();
        var failure = new IllegalStateException("the start's failure");
        var stop    = new AssertionError("a");

        var returned = Exceptions.forEachCollectingFailures(List.of("a", "b"), item -> {
            ran.add(item);
            if ("a".equals(item)) {
                throw stop;
            }
            throw failure;
        }, failure);

        assertThat(ran).containsExactly("a", "b");
        assertThat(returned).isSameAs(failure);
        assertThat(returned.getSuppressed()).as("not suppressed in itself when an item throws the given failure").containsExactly(stop);
    }

    @Test
    void forEachCollectingFailures_addsASharedInstanceToTheGivenFailureOnce_whenEveryItemThrowsIt() {
        var ran      = new ArrayList<String>();
        var failure  = new IllegalStateException("the loop's failure");
        var observer = new IllegalStateException("one instance, thrown for every item");

        var returned = Exceptions.forEachCollectingFailures(List.of("a", "b", "c"), item -> {
            ran.add(item);
            throw observer;
        }, failure);

        assertThat(ran).containsExactly("a", "b", "c");
        assertThat(returned).isSameAs(failure);
        assertThat(returned.getSuppressed()).containsExactly(observer);
    }

    @Test
    void forEachCollectingFailures_doesNotAddAnInstanceAgain_whenTheGivenFailureAlreadyCarriesItAsSuppressed() {
        var ran      = new ArrayList<String>();
        var failure  = new IllegalStateException("a nested start's failure");
        var observer = new IllegalStateException("one instance, already collected into the failure by the nested call");
        failure.addSuppressed(observer);
        var other    = new IllegalStateException("a failure the failure does not carry yet");

        var returned = Exceptions.forEachCollectingFailures(List.of("a", "b", "c"), item -> {
            ran.add(item);
            if ("b".equals(item)) {
                throw other;
            }
            throw observer;
        }, failure);

        assertThat(ran).containsExactly("a", "b", "c");
        assertThat(returned).isSameAs(failure);
        assertThat(returned.getSuppressed()).containsExactly(observer, other);
    }

    @Test
    void forEachCollectingFailures_doesNotAddAnInstanceAgain_whenTheFirstFailureAlreadyCarriesItAsSuppressed() {
        var ran      = new ArrayList<String>();
        var observer = new IllegalStateException("one instance, already collected into the first failure by a nested call");
        var first    = new IllegalStateException("the first failure, coming back from a nested call");
        first.addSuppressed(observer);
        var other    = new IllegalStateException("a failure the first failure does not carry yet");

        var returned = Exceptions.forEachCollectingFailures(List.of("a", "b", "c"), item -> {
            ran.add(item);
            if ("a".equals(item)) {
                throw first;
            }
            if ("b".equals(item)) {
                throw other;
            }
            throw observer;
        }, null);

        assertThat(ran).containsExactly("a", "b", "c");
        assertThat(returned).isSameAs(first);
        assertThat(returned.getSuppressed()).containsExactly(observer, other);
    }

    @Test
    void forEachCollectingFailures_doesNotAddAnInstanceAgain_whenANestedCallAddsItToTheGivenFailureDuringTheLoop() {
        var ran      = new ArrayList<String>();
        var failure  = new IllegalStateException("the start's failure");
        var observer = new IllegalStateException("one instance, collected into the failure by a nested call during the loop");

        var returned = Exceptions.forEachCollectingFailures(List.of("a", "b"), item -> {
            ran.add(item);
            if ("a".equals(item)) {
                // A nested undo with the same failure: it adds the observer's failure to it, and throws nothing itself
                assertThat(Exceptions.forEachCollectingFailures(List.of("inner"), inner -> {
                    throw observer;
                }, failure)).isSameAs(failure);
                return;
            }
            throw observer;
        }, failure);

        assertThat(ran).containsExactly("a", "b");
        assertThat(returned).isSameAs(failure);
        assertThat(returned.getSuppressed()).containsExactly(observer);
    }

    @Test
    void forEachCollectingFailures_doesNotAddAnInstanceAgain_whenANestedCallAddsItToTheFirstFailureDuringTheLoop() {
        var ran      = new ArrayList<String>();
        var first    = new IllegalStateException("the first failure");
        var observer = new IllegalStateException("one instance, collected into the first failure by a nested call during the loop");

        var returned = Exceptions.forEachCollectingFailures(List.of("a", "b", "c"), item -> {
            ran.add(item);
            switch (item) {
                case "a" -> throw first;
                case "b" -> Exceptions.forEachCollectingFailures(List.of("inner"), inner -> {
                    throw observer;
                }, first);
                default -> throw observer;
            }
        }, null);

        assertThat(ran).containsExactly("a", "b", "c");
        assertThat(returned).isSameAs(first);
        assertThat(returned.getSuppressed()).containsExactly(observer);
    }

    @Test
    void forEachCollectingFailures_doesNotAddTheFailureToACriticalErrorAgain_whenANestedCallAlreadyDid() {
        var ran      = new ArrayList<String>();
        var failure  = new IllegalStateException("the start's failure");
        var critical = new StackOverflowError("thrown inside a nested undo with the same failure");

        var thrown = catchThrowable(() -> Exceptions.forEachCollectingFailures(List.of("a", "b"), item -> {
            ran.add(item);
            Exceptions.forEachCollectingFailures(List.of("inner"), inner -> {
                throw critical;
            }, failure);
        }, failure));

        assertThat(thrown).isSameAs(critical);
        assertThat(thrown.getSuppressed()).containsExactly(failure);
        assertThat(ran).as("no item ran after the critical error").containsExactly("a");
    }

    @Test
    void forEachCollectingFailures_returnsNull_whenNoItemThrowsAndNoFailureIsGiven() {
        assertThat(Exceptions.forEachCollectingFailures(List.of("a"), item -> { }, null)).isNull();
    }

    @Test
    void forEachCollectingFailures_rejectsMissingArguments() {
        assertThatThrownBy(() -> Exceptions.forEachCollectingFailures(null, item -> { }, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Exceptions.forEachCollectingFailures(List.of("a"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
