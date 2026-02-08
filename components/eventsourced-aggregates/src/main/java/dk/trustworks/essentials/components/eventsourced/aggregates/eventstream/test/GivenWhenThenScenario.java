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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Single use Given/When/Then scenario runner and asserter for {@link EventStreamDecider} implementations.
 * <p>
 * This class provides a behavior-driven development (BDD) style testing API that makes it easy
 * to write clear, expressive tests for event sourcing scenarios without requiring any external
 * testing framework dependencies. It follows the Given-When-Then pattern where:
 * <ul>
 *   <li><strong>Given:</strong> Initial events that establish the current state</li>
 *   <li><strong>When:</strong> A command is executed</li>
 *   <li><strong>Then:</strong> Expected events or exceptions are verified</li>
 * </ul>
 *
 * <p>
 * Assertion exceptions thrown from {@link #then}, {@link #thenExpectNoEvent},
 * {@link #thenFailsWithException}, {@link #thenFailsWithExceptionType} are all
 * subclasses of {@link AssertionException}.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());
 *
 * var orderId = OrderId.random();
 * var customerId = CustomerId.random();
 *
 * scenario
 *     .given()  // No existing events
 *     .when(new CreateOrder(orderId, customerId))
 *     .then(new OrderCreated(orderId, customerId));
 * }</pre>
 *
 * <h3>Idempotent Behavior Testing:</h3>
 * <pre>{@code
 * scenario
 *     .given(new OrderCreated(orderId, customerId))
 *     .when(new CreateOrder(orderId, customerId))
 *     .thenExpectNoEvent();  // Idempotent behavior
 * }</pre>
 *
 * <h3>Exception Testing:</h3>
 * <pre>{@code
 * scenario
 *     .given()
 *     .when(new ConfirmOrder(orderId))
 *     .thenFailsWithExceptionType(IllegalStateException.class);
 * }</pre>
 *
 * @param <COMMAND> The type of commands the decider handles
 * @param <EVENT>   The type of events the decider produces
 * @see EventStreamDecider
 */
public class GivenWhenThenScenario<COMMAND, EVENT> {

    private final EventStreamDecider<COMMAND, EVENT> decider;
    private final List<EVENT>                        givenEvents;
    private       COMMAND                            whenCommand;
    private       EVENT                              actualEvent;
    private       Exception                          expectedException;
    private       Class<? extends Exception>         expectedExceptionType;
    private       Exception                          failedWithException;

    /**
     * Creates a new test scenario for the specified decider.
     *
     * @param decider The decider to test. Must not be null.
     * @throws IllegalArgumentException if decider is null
     */
    public GivenWhenThenScenario(EventStreamDecider<COMMAND, EVENT> decider) {
        this.decider = requireNonNull(decider, "decider cannot be null");
        this.givenEvents = new ArrayList<>();
    }

    /**
     * Set up the test scenario by providing past events that will be provided to
     * the {@link EventStreamDecider#handle} method as an event stream.
     * <p>
     * This step is also known as the <strong>Arrange</strong> step in Arrange, Act, Assert.
     * <p>
     * In case this method isn't called, then the default value for givenEvents is an empty list.
     *
     * @param events The past events related to the aggregate instance (can be empty). Must not be null.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws IllegalArgumentException if events is null
     */
    @SafeVarargs
    public final GivenWhenThenScenario<COMMAND, EVENT> given(EVENT... events) {
        requireNonNull(events, "events cannot be null");

        givenEvents.clear();
        givenEvents.addAll(Arrays.asList(events));
        return this;
    }

    /**
     * Set up the test scenario by providing past events that will be provided to
     * the {@link EventStreamDecider#handle} method as an event stream.
     *
     * @param events The list of past events related to the aggregate instance. Must not be null.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws IllegalArgumentException if events is null
     */
    public GivenWhenThenScenario<COMMAND, EVENT> given(List<EVENT> events) {
        requireNonNull(events, "events cannot be null");

        givenEvents.clear();
        givenEvents.addAll(events);
        return this;
    }

    /**
     * Define the command that will be supplied to the {@link EventStreamDecider#handle} as the command
     * when one of the <strong>then</strong> methods are called.
     * <p>
     * This step is also known as the <strong>Act</strong> step in Arrange, Act, Assert.
     *
     * @param command The command to execute. Must not be null.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws IllegalArgumentException if command is null
     */
    public GivenWhenThenScenario<COMMAND, EVENT> when(COMMAND command) {
        this.whenCommand = requireNonNull(command, "command cannot be null");
        return this;
    }

    /**
     * Define the expected event outcome when GivenWhenThenScenario is calling the
     * {@link EventStreamDecider#handle} with the command provided in {@link #when}
     * and past events provided in {@link #given}.
     * <p>
     * This step is also known as the <strong>Assert</strong> step in Arrange, Act, Assert.
     *
     * @param expectedEvent The event we expect to be returned from the {@link EventStreamDecider#handle(Object, List)} method
     *                      as a result of handling the whenCommand using the givenEvents past events.
     *                      Can be null to indicate no event is expected.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws NoCommandProvidedException                   if no command was provided via when()
     * @throws DidNotExpectAnEventException                 if expectedEvent is null but an event was produced
     * @throws ExpectedAnEventButDidNotGetAnyEventException if expectedEvent is not null but no event was produced
     * @throws ActualAndExpectedEventsAreNotEqualException  if the actual event doesn't equal the expected event
     * @throws FailedWithUnexpectedException                if the decider threw an unexpected exception
     */
    public GivenWhenThenScenario<COMMAND, EVENT> then(EVENT expectedEvent) {
        if (whenCommand == null) {
            throw new NoCommandProvidedException();
        }

        try {
            var optionalEvent = decider.handle(whenCommand, List.copyOf(givenEvents));
            actualEvent = optionalEvent.orElse(null);

            if (!Objects.equals(actualEvent, expectedEvent)) {
                if (expectedEvent == null) {
                    throw new DidNotExpectAnEventException(actualEvent);
                }
                if (actualEvent == null) {
                    throw new ExpectedAnEventButDidNotGetAnyEventException(expectedEvent);
                }
                throw new ActualAndExpectedEventsAreNotEqualException(expectedEvent, actualEvent);
            }

            return this;
        } catch (Exception e) {
            throw new FailedWithUnexpectedException(e);
        }
    }

    /**
     * Define the expected event outcome when {@link GivenWhenThenScenario} is calling the
     * {@link EventStreamDecider#handle} with the command provided in {@link #when}
     * and past events provided in {@link #given(List)}.
     * <p>
     * This method allows for custom assertion logic on the actual event.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * scenario
     *     .given()
     *     .when(new CreateOrder(orderId, customerId))
     *     .thenAssert(actualEvent -> {
     *         if (actualEvent == null) {
     *             throw new AssertionException("Expected an event but got null");
     *         }
     *         if (!(actualEvent instanceof OrderCreated)) {
     *             throw new AssertionException("Expected OrderCreated but got " + actualEvent.getClass());
     *         }
     *         var created = (OrderCreated) actualEvent;
     *         if (!created.orderId().equals(orderId)) {
     *             throw new AssertionException("Order ID mismatch");
     *         }
     *     });
     * }</pre>
     *
     * @param actualEventAsserter A Consumer that will receive the actual event that resulted from handling
     *                            the whenCommand using the givenEvents past events. This Consumer is expected
     *                            to manually assert the content of the actual event.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws NoCommandProvidedException    if no command was provided via when()
     * @throws FailedWithUnexpectedException if the decider threw an unexpected exception
     */
    public GivenWhenThenScenario<COMMAND, EVENT> thenAssert(Consumer<EVENT> actualEventAsserter) {
        if (whenCommand == null) {
            throw new NoCommandProvidedException();
        }

        try {
            var optionalEvent = decider.handle(whenCommand, List.copyOf(givenEvents));
            actualEvent = optionalEvent.orElse(null);
            actualEventAsserter.accept(actualEvent);
            return this;
        } catch (Exception e) {
            throw new FailedWithUnexpectedException(e);
        }
    }

    /**
     * Define that we don't expect any events outcome when {@link GivenWhenThenScenario} is calling the
     * {@link EventStreamDecider#handle} with the command provided in {@link #when}
     * and past events provided in {@link #given(List)}.
     * <p>
     * This is equivalent to calling {@code then(null)}.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * scenario
     *     .given(new OrderCreated(orderId, customerId))
     *     .when(new CreateOrder(orderId, customerId))
     *     .thenExpectNoEvent();  // Idempotent behavior
     * }</pre>
     *
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws NoCommandProvidedException    if no command was provided via when()
     * @throws DidNotExpectAnEventException  if an event was produced
     * @throws FailedWithUnexpectedException if the decider threw an unexpected exception
     */
    public GivenWhenThenScenario<COMMAND, EVENT> thenExpectNoEvent() {
        return then(null);
    }

    /**
     * Define that we expect the scenario to fail with a specific exception instance
     * when the GivenWhenThenScenario is calling the {@link EventStreamDecider#handle}
     * with the command provided in {@link #when} and past events provided in {@link #given(Object[])}.
     * <p>
     * This method compares both the exception type and the exception message.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * scenario
     *     .given()
     *     .when(new ConfirmOrder(orderId))
     *     .thenFailsWithException(new IllegalStateException("Cannot confirm order that does not exist"));
     * }</pre>
     *
     * @param expectedException The exception that we expect the  {@link EventStreamDecider#handle(Object, List)} to throw
     *                          when handling the whenCommand using the givenEvents past events.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws NoCommandProvidedException                             if no command was provided via when()
     * @throws ExpectedToFailWithAnExceptionButNoneWasThrownException if no exception was thrown
     * @throws ActualExceptionIsNotEqualToExpectedException           if a different exception was thrown
     */
    public GivenWhenThenScenario<COMMAND, EVENT> thenFailsWithException(Exception expectedException) {
        if (whenCommand == null) {
            throw new NoCommandProvidedException();
        }

        this.expectedException = expectedException;
        try {
            decider.handle(whenCommand, List.copyOf(givenEvents));
            throw new ExpectedToFailWithAnExceptionButNoneWasThrownException(expectedException);
        } catch (Exception actualException) {
            if (!actualException.getClass().equals(expectedException.getClass())) {
                throw new ActualExceptionIsNotEqualToExpectedException(expectedException, actualException);
            }
            if (!Objects.equals(actualException.getMessage(), expectedException.getMessage())) {
                throw new ActualExceptionIsNotEqualToExpectedException(expectedException, actualException);
            }
            return this;
        }
    }

    /**
     * Define that we expect the scenario to fail with a specific exception type
     * when the {@link GivenWhenThenScenario} is calling the {@link EventStreamDecider#handle}
     * with the command provided in {@link #when} and past events provided in {@link #given(Object[])}.
     * <p>
     * This method only compares the exception type, not the message.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * scenario
     *     .given()
     *     .when(new ConfirmOrder(orderId))
     *     .thenFailsWithExceptionType(IllegalStateException.class);
     * }</pre>
     *
     * @param expectedExceptionType The exception type that we expect the  {@link EventStreamDecider#handle(Object, List)} to throw
     *                              when handling the whenCommand using the givenEvents past events.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws NoCommandProvidedException                                 if no command was provided via when()
     * @throws ExpectedToFailWithAnExceptionTypeButNoneWasThrownException if no exception was thrown
     * @throws ActualExceptionTypeIsNotEqualToExpectedException           if a different exception type was thrown
     */
    public GivenWhenThenScenario<COMMAND, EVENT> thenFailsWithExceptionType(Class<? extends Exception> expectedExceptionType) {
        if (whenCommand == null) {
            throw new NoCommandProvidedException();
        }

        this.expectedExceptionType = expectedExceptionType;
        try {
            decider.handle(whenCommand, List.copyOf(givenEvents));
            throw new ExpectedToFailWithAnExceptionTypeButNoneWasThrownException(expectedExceptionType);
        } catch (Exception actualException) {
            failedWithException = actualException;
            if (!actualException.getClass().equals(expectedExceptionType)) {
                throw new ActualExceptionTypeIsNotEqualToExpectedException(expectedExceptionType, actualException);
            }
            return this;
        }
    }

    /**
     * Define that we expect the scenario to fail with a specific exception type and message
     * when the GivenWhenThenScenario is calling the {@link EventStreamDecider#handle}
     * with the command provided in {@link #when} and past events provided in {@link #given(Object[])}.
     * <p>
     * This method compares both the exception type and the message. It reuses the existing
     * {@link #thenFailsWithExceptionType(Class)} method for type checking and adds message validation.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * scenario
     *     .given()
     *     .when(new ConfirmOrder(orderId))
     *     .thenFailsWithExceptionType(IllegalStateException.class, "Cannot confirm order that does not exist");
     * }</pre>
     *
     * @param expectedExceptionType The exception type that we expect the {@link EventStreamDecider#handle(Object, List)} to throw
     *                              when handling the whenCommand using the givenEvents past events.
     * @param expectedMessage       The expected exception message.
     * @return this GivenWhenThenScenario instance for fluent chaining
     * @throws NoCommandProvidedException                                 if no command was provided via when()
     * @throws ExpectedToFailWithAnExceptionTypeButNoneWasThrownException if no exception was thrown
     * @throws ActualExceptionTypeIsNotEqualToExpectedException           if a different exception type was thrown
     * @throws ActualExceptionIsNotEqualToExpectedException               if the exception message doesn't match
     */
    public GivenWhenThenScenario<COMMAND, EVENT> thenFailsWithExceptionType(Class<? extends Exception> expectedExceptionType, String expectedMessage) {
        // First validate the exception type by reusing the existing method
        thenFailsWithExceptionType(expectedExceptionType);

        if (!Objects.equals(failedWithException.getMessage(), expectedMessage)) {
            throw new ActualExceptionMessageIsNotEqualToExpectedMessageException(failedWithException, expectedMessage);
        }
        return this;
    }

    /**
     * Returns the decider being tested.
     *
     * @return The decider
     */
    public EventStreamDecider<COMMAND, EVENT> getDecider() {
        return decider;
    }

    /**
     * Returns a copy of the given events.
     *
     * @return The given events
     */
    public List<EVENT> getGivenEvents() {
        return List.copyOf(givenEvents);
    }

    /**
     * Returns the command that was executed.
     *
     * @return The command, or null if when() hasn't been called
     */
    public COMMAND getWhenCommand() {
        return whenCommand;
    }

    /**
     * Returns the actual event that was produced.
     *
     * @return The actual event, or null if no event was produced or if execution hasn't happened yet
     */
    public EVENT getActualEvent() {
        return actualEvent;
    }

    // ========== Exception Classes ==========

    /**
     * Base class for all assertion exceptions thrown by GivenWhenThenScenario.
     */
    public static abstract class AssertionException extends RuntimeException {
        public AssertionException() {
            super();
        }

        public AssertionException(String message) {
            super(message);
        }

        public AssertionException(String message, Throwable cause) {
            super(message, cause);
        }

        public AssertionException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Thrown when no command was provided via when() before calling a then method.
     */
    public static class NoCommandProvidedException extends AssertionException {
        public NoCommandProvidedException() {
            super("No command provided. Call when() before calling then methods.");
        }
    }

    /**
     * Thrown when the decider throws an unexpected exception.
     */
    public static class FailedWithUnexpectedException extends AssertionException {
        private final Exception unexpectedException;

        public FailedWithUnexpectedException(Exception unexpectedException) {
            super("Decider failed with unexpected exception", unexpectedException);
            this.unexpectedException = unexpectedException;
        }

        public Exception getUnexpectedException() {
            return unexpectedException;
        }
    }

    /**
     * Thrown when expecting an exception but none was thrown.
     */
    public static class ExpectedToFailWithAnExceptionButNoneWasThrownException extends AssertionException {
        private final Exception expectedException;

        public ExpectedToFailWithAnExceptionButNoneWasThrownException(Exception expectedException) {
            super("Expected to fail with exception " + expectedException.getClass().getSimpleName() +
                          " but no exception was thrown");
            this.expectedException = expectedException;
        }

        public Exception getExpectedException() {
            return expectedException;
        }
    }

    /**
     * Thrown when expecting an exception type but none was thrown.
     */
    public static class ExpectedToFailWithAnExceptionTypeButNoneWasThrownException extends AssertionException {
        private final Class<? extends Exception> expectedExceptionType;

        public ExpectedToFailWithAnExceptionTypeButNoneWasThrownException(Class<? extends Exception> expectedExceptionType) {
            super("Expected to fail with exception type " + expectedExceptionType.getSimpleName() +
                          " but no exception was thrown");
            this.expectedExceptionType = expectedExceptionType;
        }

        public Class<? extends Exception> getExpectedExceptionType() {
            return expectedExceptionType;
        }
    }

    /**
     * Thrown when the actual exception type doesn't match the expected exception type.
     */
    public static class ActualExceptionTypeIsNotEqualToExpectedException extends AssertionException {
        private final Class<? extends Exception> expectedExceptionType;
        private final Exception                  actualException;

        public ActualExceptionTypeIsNotEqualToExpectedException(Class<? extends Exception> expectedExceptionType, Exception actualException) {
            super("Expected exception type " + expectedExceptionType.getSimpleName() +
                          " but got " + actualException.getClass().getSimpleName(), actualException);
            this.expectedExceptionType = expectedExceptionType;
            this.actualException = actualException;
        }

        public Class<? extends Exception> getExpectedExceptionType() {
            return expectedExceptionType;
        }

        public Exception getActualException() {
            return actualException;
        }
    }

    /**
     * Thrown when the actual exception doesn't match the expected exception.
     */
    public static class ActualExceptionIsNotEqualToExpectedException extends AssertionException {
        private final Exception expectedException;
        private final Exception actualException;

        public ActualExceptionIsNotEqualToExpectedException(Exception expectedException, Exception actualException) {
            super("Expected exception " + expectedException.getClass().getSimpleName() +
                          " with message '" + expectedException.getMessage() + "' but got " +
                          actualException.getClass().getSimpleName() + " with message '" + actualException.getMessage() + "'",
                  actualException);
            this.expectedException = expectedException;
            this.actualException = actualException;
        }

        public Exception getExpectedException() {
            return expectedException;
        }

        public Exception getActualException() {
            return actualException;
        }
    }

    /**
     * Thrown when an event was produced but none was expected.
     */
    public static class DidNotExpectAnEventException extends AssertionException {
        private final Object actualEvent;

        public DidNotExpectAnEventException(Object actualEvent) {
            super("Did not expect an event but got: " + actualEvent);
            this.actualEvent = actualEvent;
        }

        public Object getActualEvent() {
            return actualEvent;
        }
    }

    /**
     * Thrown when no event was produced but one was expected.
     */
    public static class ExpectedAnEventButDidNotGetAnyEventException extends AssertionException {
        private final Object expectedEvent;

        public ExpectedAnEventButDidNotGetAnyEventException(Object expectedEvent) {
            super("Expected event " + expectedEvent + " but no event was produced");
            this.expectedEvent = expectedEvent;
        }

        public Object getExpectedEvent() {
            return expectedEvent;
        }
    }

    /**
     * Thrown when the actual event doesn't match the expected event.
     */
    public static class ActualAndExpectedEventsAreNotEqualException extends AssertionException {
        private final Object expectedEvent;
        private final Object actualEvent;

        public ActualAndExpectedEventsAreNotEqualException(Object expectedEvent, Object actualEvent) {
            super("Expected event: " + expectedEvent + " but got actual event: " + actualEvent);
            this.expectedEvent = expectedEvent;
            this.actualEvent = actualEvent;
        }

        public Object getExpectedEvent() {
            return expectedEvent;
        }

        public Object getActualEvent() {
            return actualEvent;
        }
    }

    public static class ActualExceptionMessageIsNotEqualToExpectedMessageException extends AssertionException {
        private final Throwable actualException;
        private final String    expectedMessage;

        public ActualExceptionMessageIsNotEqualToExpectedMessageException(Throwable actualException, String expectedMessage) {
            super(msg("Expected exception '{}' to have message '{}' but it has messages '{}'", actualException.getClass().getSimpleName(), expectedMessage, actualException.getMessage()));
            this.actualException = actualException;
            this.expectedMessage = expectedMessage;
        }

        public Throwable getActualException() {
            return actualException;
        }

        public String getExpectedMessage() {
            return expectedMessage;
        }
    }
}
