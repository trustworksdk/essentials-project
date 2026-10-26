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

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public final class Exceptions {
    /**
     * Prefix of the root-cause summary {@link #prependRootCauseSummary(Throwable, String)} puts in front of the
     * description of a wrapped throwable
     */
    public static final String ROOT_CAUSE_PREFIX = "Root cause: ";

    /**
     * Allows us to sneaky throw a checked exception without having to wrap it<br>
     * Use with caution
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
        requireNonNull(t, "You must supply an exception");
        throw (T) t;
    }

    /**
     * Get the stacktrace of the <code>throwable</code> as a String
     *
     * @param throwable the exception
     * @return the <code>throwable</code>'s full stacktrace
     */
    public static String getStackTrace(Throwable throwable) {
        requireNonNull(throwable, "You must specify a throwable");
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Get the root cause (top most parent) of an Exception.
     *
     * @param exception the exception we want the root cause of
     * @return the root cause of the exception - will never be null
     */
    public static Throwable getRootCause(Throwable exception) {
        requireNonNull(exception, "You must supply an exception");

        var visited        = new HashSet<Throwable>();  // Track visited exceptions to prevent infinite loops
        var current        = exception;
        var lastValidCause = current;

        while (current != null) {
            if (!visited.add(current)) {
                break; // Circular reference detected
            }
            lastValidCause = current;
            current = current.getCause();
        }

        return lastValidCause;
    }

    /**
     * Puts a summary of <code>throwable</code>'s root cause (see {@link #getRootCause(Throwable)}) in front of
     * <code>description</code>, when <code>throwable</code> wraps another throwable.
     * <p>
     * A wrapped exception's own text names the wrapper and hides why the operation failed, so a stored or logged
     * description of it is easier to read when it leads with the root cause. The summary is {@value #ROOT_CAUSE_PREFIX},
     * followed by the root cause's {@link Throwable#toString()} ({@code <type>: <message>}, or just {@code <type>}
     * when the root cause has no message) and {@link System#lineSeparator()}. The caller decides what
     * <code>description</code> is, e.g. {@link #getStackTrace(Throwable)} or <code>throwable.toString()</code>.
     * <p>
     * The summary is not guaranteed to be a single line: the root cause's message is used verbatim, and may itself
     * contain line breaks — PostgreSQL errors, for example, often carry {@code Detail:} or {@code Where:} lines. Do not
     * take only the first line of the result as the complete root cause.
     *
     * @param throwable   the throwable <code>description</code> describes
     * @param description the text describing <code>throwable</code>
     * @return <code>description</code> unchanged when <code>throwable</code> has no cause; otherwise the root-cause
     * summary followed by <code>description</code>
     */
    public static String prependRootCauseSummary(Throwable throwable, String description) {
        requireNonNull(throwable, "You must supply an exception");
        requireNonNull(description, "You must supply a description");
        var rootCause = getRootCause(throwable);
        if (rootCause == throwable) {
            return description;
        }
        return ROOT_CAUSE_PREFIX + rootCause + System.lineSeparator() + description;
    }

    /**
     * Check if the stack trace (including causes) contains an exception of a specified type.
     *
     * @param exception     the root exception
     * @param exceptionType the type of exception to look for
     * @return true if the exception or one of its causes matches the specified type, false otherwise
     */
    public static boolean doesStackTraceContainExceptionOfType(Throwable exception, Class<?> exceptionType) {
        requireNonNull(exception, "You must supply an exception");
        requireNonNull(exceptionType, "You must supply an exception type");

        var visited = new HashSet<Throwable>(); // Track visited exceptions to prevent infinite loops
        var current = exception;

        while (current != null) {
            if (!visited.add(current)) {
                break;  // Circular reference detected
            }
            visited.add(current);

            if (exceptionType.isAssignableFrom(current.getClass())) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    /**
     * Rethrows a {@link Throwable} (or anything in its causal chain), using {@link #sneakyThrow(Throwable)},
     * if the exception represents a critical, unrecoverable error that should not be caught/handled
     * by normal application logic.
     * <p>
     * This includes:
     * <ul>
     *   <li>{@link VirtualMachineError} (e.g., {@link OutOfMemoryError}, {@link StackOverflowError})</li>
     *   <li>{@link ThreadDeath}</li>
     *   <li>{@link LinkageError}</li>
     * </ul>
     * <p>
     * In addition, if such an error appears as a suppressed exception or as the
     * cause of another exception, it is also treated as critical.
     *
     * @param throwable the {@link Throwable} to check
     * @see #isCriticalError(Throwable)
     */
    public static <T extends Throwable> void rethrowIfCriticalError(T throwable) {
        requireNonNull(throwable, "You must supply an exception");
        if (isCriticalError(throwable)) {
            Exceptions.sneakyThrow(throwable);
        }
    }

    /**
     * Runs <code>action</code> for every one of <code>items</code>, each one even when it throws for an earlier one, and then
     * throws the first failure - as it was thrown, using {@link #sneakyThrow(Throwable)} - with every later, distinct
     * failure added to it as suppressed. Distinct is by identity: an instance thrown for several items is added once.
     * <p>
     * Meant for fanning one operation out over independent parts, such as stopping every consumer of a queue, where
     * giving up at the first throw would leave the parts after it untouched (still running, say).
     * <p>
     * A {@link RuntimeException}, a non-critical {@link Error} (an {@link AssertionError}, for example) and a checked
     * exception the action throws without declaring it (with {@link #sneakyThrow(Throwable)}, or from Kotlin) are
     * collected alike. A critical error ({@link #isCriticalError(Throwable)}) is rethrown at once, without running
     * <code>action</code> for the items after it, with the failure collected so far added to it as suppressed, unless it
     * already carries it.
     *
     * @param items  the items to run <code>action</code> for, in their iteration order
     * @param action the action to run for each item
     * @param <T>    the type of the items
     * @see #forEachCollectingFailures(Iterable, Consumer, Throwable)
     */
    public static <T> void forEachThenThrowFirst(Iterable<? extends T> items, Consumer<? super T> action) {
        var failure = forEachCollectingFailures(items, action, null);
        if (failure != null) {
            sneakyThrow(failure);
        }
    }

    /**
     * {@link #forEachThenThrowFirst(Iterable, Consumer)}, adding every failure to <code>failure</code> when there already is
     * one - the failure of a start that the action undoes, say - and returning the failure instead of throwing it, so the
     * caller throws it with its own type. A critical error is thrown at once all the same, with <code>failure</code> added
     * to it as suppressed.
     * <p>
     * Each failure is added once, by identity: an instance thrown for several items is added once, and one that the
     * failure it would be added to already carries as suppressed when it is about to be added (collected into it by a
     * nested call, before this call or during it, say) is not added again. That failure is <code>failure</code> when it is
     * not null, otherwise the first failure of <code>action</code>. The same holds for a critical error: that failure is
     * not added to it when it already carries it (a nested call with the same <code>failure</code> that hit the critical
     * error added it first).
     *
     * @param items   the items to run <code>action</code> for, in their iteration order
     * @param action  the action to run for each item
     * @param failure the failure every failure of <code>action</code> is added to as suppressed; may be null
     * @param <T>     the type of the items
     * @return <code>failure</code> when it is not null; otherwise the first failure of <code>action</code>, with every
     * later, distinct one added to it as suppressed; null when <code>failure</code> is null and <code>action</code> threw
     * for no item
     */
    public static <T> Throwable forEachCollectingFailures(Iterable<? extends T> items, Consumer<? super T> action, Throwable failure) {
        requireNonNull(items, "You must supply the items");
        requireNonNull(action, "You must supply an action");
        var collected = failure;
        for (var item : items) {
            try {
                action.accept(item);
            } catch (Throwable e) {
                // Throwable, not RuntimeException | Error: a checked exception thrown without being declared (sneakily - a
                // Kotlin action can) is collected too, so it neither skips the items after it nor replaces the failure
                if (isCriticalError(e)) {
                    if (collected != null) {
                        addSuppressedOnce(e, collected);
                    }
                    sneakyThrow(e);
                }
                if (collected == null) {
                    collected = e;
                } else {
                    addSuppressedOnce(collected, e);
                }
            }
        }
        return collected;
    }

    /**
     * Adds <code>suppressed</code> to <code>holder</code> as suppressed ({@link Throwable#addSuppressed(Throwable)}), unless
     * it is <code>holder</code> itself or <code>holder</code> carries it as suppressed already - read at the time of the
     * call, so one added to <code>holder</code> after an earlier read (by a nested call, say) is not added again.
     * <p>
     * By identity, not <code>equals()</code>: {@link Throwable#addSuppressed(Throwable)} throws
     * {@link IllegalArgumentException} for the throwable itself, which it tells by identity, so a cleanup loop that adds
     * each failure to the one it keeps would otherwise end at a failure thrown again as the same instance (a JVM can
     * throw one {@link OutOfMemoryError} instance more than once), leaving the items after it not run.
     * {@link #forEachCollectingFailures(Iterable, Consumer, Throwable)} adds every failure through it.
     *
     * @param holder     the throwable to add <code>suppressed</code> to
     * @param suppressed the throwable to add to <code>holder</code> as suppressed
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public static void addSuppressedOnce(Throwable holder, Throwable suppressed) {
        requireNonNull(holder, "You must supply the holder");
        requireNonNull(suppressed, "You must supply the throwable to suppress");
        if (holder == suppressed) {
            return;
        }
        for (var carried : holder.getSuppressed()) {
            if (carried == suppressed) {
                return;
            }
        }
        holder.addSuppressed(suppressed);
    }

    /**
     * Determines whether a {@link Throwable} (or anything in its causal chain)
     * represents a critical, unrecoverable error that should not be caught/handled
     * by normal application logic.
     * <p>
     * This includes:
     * <ul>
     *   <li>{@link VirtualMachineError} (e.g., {@link OutOfMemoryError}, {@link StackOverflowError})</li>
     *   <li>{@link ThreadDeath}</li>
     *   <li>{@link LinkageError}</li>
     * </ul>
     * <p>
     * In addition, if such an error appears as a suppressed exception or as the
     * cause of another exception, it is also treated as critical.
     *
     * @param t the {@link Throwable} to check
     * @return {@code true} if the throwable (or any suppressed/caused throwable)
     * is considered critical; {@code false} otherwise
     */
    public static boolean isCriticalError(Throwable t) {
        return isCriticalErrorRecursive(t, new HashSet<>());
    }

    /**
     * Recursive helper method that traverses causes and suppressed exceptions,
     * keeping track of already visited {@code Throwable} instances to avoid
     * infinite loops if there is a cyclical cause/suppressed chain.
     */
    private static boolean isCriticalErrorRecursive(Throwable t, Set<Throwable> visited) {
        if (t == null || visited.contains(t)) {
            return false;
        }
        visited.add(t);

        // Is this exception one of the critical types
        if (t instanceof VirtualMachineError ||
                t instanceof ThreadDeath ||
                t instanceof LinkageError) {
            return true;
        }

        // Check suppressed exceptions
        for (var suppressed : t.getSuppressed()) {
            if (isCriticalErrorRecursive(suppressed, visited)) {
                return true;
            }
        }

        // Check the cause of this exception
        return isCriticalErrorRecursive(t.getCause(), visited);
    }
}