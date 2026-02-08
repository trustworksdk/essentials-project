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

package dk.trustworks.essentials.shared.functional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Variant of {@link QuadFunction} that behaves like {@link QuadFunction}, but which allows checked {@link Exception}'s to be thrown
 * from its {@link #apply(Object, Object, Object, Object)} method<br>
 *
 *
 * <T1> – the first function argument type
 * <T2> – the second function argument type
 * <T3> – the third function argument type
 * <T4> – the fourth function argument type
 * <R> – the function result type
 *
 * @see #safe(CheckedQuadFunction)
 * @see #safe(String, CheckedQuadFunction)
 */
@FunctionalInterface
public interface CheckedQuadFunction<T1, T2, T3, T4, R> {
    /**
     * Wraps a {@link CheckedQuadFunction} (basically a lambda with four arguments that returns a result and which throws a Checked {@link Exception}) by returning a new {@link QuadFunction} instance<br>
     * The returned {@link QuadFunction#apply(Object, Object, Object, Object)} method delegates directly to the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} and catches any thrown checked {@link Exception}'s and
     * rethrows them as a {@link CheckedExceptionRethrownException}<br>
     * Unless you provide a context-message (using {@link #safe(String, CheckedQuadFunction)} then any caught checked {@link Exception}'s message also becomes the {@link CheckedExceptionRethrownException}'s message.<br>
     * Any {@link RuntimeException}'s thrown aren't caught and the calling code will receive the original {@link RuntimeException} thrown.<br>
     * <br>
     * Usage example:<br>
     * Let's say we have a method called <code>someOperation</code> that cannot change, but which accepts a {@link QuadFunction} with the purpose of the calling the {@link QuadFunction#apply(Object, Object, Object, Object)}.<br>
     * <pre>{@code
     * public void someOperation(Function<Integer, BigDecimal, Boolean, String, String> operation) {
     *      // ... Logic ...
     *
     *      String value = operation.apply(10, BigDecimal.ONE, true, "test");
     *
     *      // ... More logic ---
     * }
     * }</pre>
     * The problem will {@link QuadFunction#apply(Object, Object, Object, Object)} occurs when {@link QuadFunction} calls any API that throws a checked {@link Exception}, e.g. the {@link java.io.File} API.<br>
     * Since {@link QuadFunction#apply(Object, Object, Object, Object)} doesn't define that it throws any {@link Exception}'s we're forced to add a try/catch to handle the {@link java.io.IOException} for the code to compile:
     * <pre>{@code
     *  someOperation((value, amount, enabled, name) -> {
     *                      try {
     *                          // Logic that uses the File API
     *                          return "some-value";
     *                      } catch (IOException e) {
     *                          throw new RuntimeException(e);
     *                      }
     *                }));
     * }</pre>
     * <br>
     * This is where the {@link CheckedQuadFunction} comes to the aid as its {@link #apply(Object, Object, Object, Object)} methods defines that it throws a Checked {@link Exception} and its {@link #safe(CheckedQuadFunction)}
     * will return a new {@link QuadFunction} instance with a {@link QuadFunction#apply(Object, Object, Object, Object)} method that ensures that the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} method is called
     * and any checked {@link Exception}'s thrown will be
     * caught and rethrown as a {@link CheckedExceptionRethrownException}:
     * <pre>{@code
     *  someOperation(CheckedFunction.safe((value, amount, enabled, name) -> {
     *                      // Logic that uses the File API that throws IOException
     *                      return "some-value";
     *                }));
     * }
     * </pre>
     *
     * @param functionThatCanFailWithACheckedException the {@link CheckedQuadFunction} instance that will be wrapped as a {@link QuadFunction}
     * @param <T1>                                     the first argument type
     * @param <T2>                                     the second argument type
     * @param <T3>                                     the third argument type
     * @param <T4>                                     the fourth argument type
     * @param <R>                                      the return type
     * @return a {@link QuadFunction} with a {@link QuadFunction#apply(Object, Object, Object, Object)} method that ensures that the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} method is called when
     * {@link QuadFunction#apply(Object, Object, Object, Object)} and any checked {@link Exception}'s thrown will be
     * caught and rethrown as a {@link CheckedExceptionRethrownException}.<br>
     * Any {@link RuntimeException}'s thrown aren't caught and the calling code will receive the original {@link RuntimeException} thrown.
     * @throws CheckedExceptionRethrownException in case the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} method throws a checked {@link Exception}
     */
    static <T1, T2, T3, T4, R> QuadFunction<T1, T2, T3, T4, R> safe(CheckedQuadFunction<T1, T2, T3, T4, R> functionThatCanFailWithACheckedException) {
        requireNonNull(functionThatCanFailWithACheckedException, "No CheckedQuadFunction instance provided");
        return (t1, t2, t3, t4) -> {
            try {
                return functionThatCanFailWithACheckedException.apply(t1, t2, t3, t4);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CheckedExceptionRethrownException(e.getMessage(), e);
            }
        };
    }

    /**
     * Wraps a {@link CheckedQuadFunction} (basically a lambda with four arguments that returns a result and which throws a Checked {@link Exception}) by returning a new {@link QuadFunction} instance<br>
     * The returned {@link QuadFunction#apply(Object, Object, Object, Object)} method delegates directly to the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} and catches any thrown checked {@link Exception}'s and
     * rethrows them as a {@link CheckedExceptionRethrownException}<br>
     * Any {@link RuntimeException}'s thrown aren't caught and the calling code will receive the original {@link RuntimeException} thrown.<br>
     * <br>
     * Usage example:<br>
     * Let's say we have a method called <code>someOperation</code> that cannot change, but which accepts a {@link QuadFunction} with the purpose of the calling the {@link QuadFunction#apply(Object, Object, Object, Object)}.<br>
     * <pre>{@code
     * public void someOperation(Function<Integer, BigDecimal, Boolean, String, String> operation) {
     *      // ... Logic ...
     *
     *      String value = operation.apply(10, BigDecimal.ONE, true, "test");
     *
     *      // ... More logic ---
     * }
     * }</pre>
     * The problem will {@link QuadFunction#apply(Object, Object, Object, Object)} occurs when {@link QuadFunction} calls any API that throws a checked {@link Exception}, e.g. the {@link java.io.File} API.<br>
     * Since {@link QuadFunction#apply(Object, Object, Object, Object)} doesn't define that it throws any {@link Exception}'s we're forced to add a try/catch to handle the {@link java.io.IOException} for the code to compile:
     * <pre>{@code
     *  someOperation((value, amount, enabled, name) -> {
     *                      try {
     *                          // Logic that uses the File API
     *                          return "some-value";
     *                      } catch (IOException e) {
     *                          throw new RuntimeException(e);
     *                      }
     *                }));
     * }</pre>
     * <br>
     * This is where the {@link CheckedQuadFunction} comes to the aid as its {@link #apply(Object, Object, Object, Object)} methods defines that it throws a Checked {@link Exception} and its {@link #safe(CheckedQuadFunction)}
     * will return a new {@link QuadFunction} instance with a {@link QuadFunction#apply(Object, Object, Object, Object)} method that ensures that the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} method is called
     * and any checked {@link Exception}'s thrown will be
     * caught and rethrown as a {@link CheckedExceptionRethrownException}:
     * <pre>{@code
     *  someOperation(CheckedFunction.safe(msg("Processing file {}", fileName),
     *                (value, amount, enabled, name) -> {
     *                      // Logic that uses the File API that throws IOException
     *                      return "some-value";
     *                }));
     * }
     * </pre>
     *
     * @param contextMessage                           a string that the described the content for the {@link CheckedQuadFunction} and which will be used as message in any {@link CheckedExceptionRethrownException} thrown
     * @param functionThatCanFailWithACheckedException the {@link CheckedQuadFunction} instance that will be wrapped as a {@link QuadFunction}
     * @param <T1>                                     the first argument type
     * @param <T2>                                     the second argument type
     * @param <T3>                                     the third argument type
     * @param <T4>                                     the fourth argument type
     * @param <R>                                      the return type
     * @return a {@link QuadFunction} with a {@link QuadFunction#apply(Object, Object, Object, Object)} method that ensures that the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} method is called when
     * {@link QuadFunction#apply(Object, Object, Object, Object)} and any checked {@link Exception}'s thrown will be
     * caught and rethrown as a {@link CheckedExceptionRethrownException}.<br>
     * Any {@link RuntimeException}'s thrown aren't caught and the calling code will receive the original {@link RuntimeException} thrown.
     * @throws CheckedExceptionRethrownException in case the {@link CheckedQuadFunction#apply(Object, Object, Object, Object)} method throws a checked {@link Exception}
     */
    static <T1, T2, T3, T4, R> QuadFunction<T1, T2, T3, T4, R> safe(String contextMessage,
                                                                     CheckedQuadFunction<T1, T2, T3, T4, R> functionThatCanFailWithACheckedException) {
        requireNonNull(contextMessage, "No contextMessage provided");
        requireNonNull(functionThatCanFailWithACheckedException, "No CheckedQuadFunction instance provided");
        return (t1, t2, t3, t4) -> {
            try {
                return functionThatCanFailWithACheckedException.apply(t1, t2, t3, t4);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CheckedExceptionRethrownException(contextMessage, e);
            }
        };
    }

    /**
     * This method performs the operation that may fail with a Checked {@link Exception}
     *
     * @param arg1 the first function argument
     * @param arg2 the second function argument
     * @param arg3 the third function argument
     * @param arg4 the fourth function argument
     * @return the result of performing the function
     * @throws Exception this method can throw both checked {@link Exception}'s as well as {@link RuntimeException}'s
     */
    R apply(T1 arg1, T2 arg2, T3 arg3, T4 arg4) throws Exception;
}