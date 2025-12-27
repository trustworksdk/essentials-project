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

package dk.trustworks.essentials.shared.functional.tuple;

import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A specialized {@link Either} for representing the result of an operation that can either
 * succeed with a value or fail with an error.<br>
 * <br>
 * This class provides semantic naming for the common success/error use case:
 * <ul>
 *   <li>{@link #error()} corresponds to {@link Either#_1()}</li>
 *   <li>{@link #success()} corresponds to {@link Either#_2()}</li>
 * </ul>
 * <br>
 * <b>Example usage:</b>
 * <pre>{@code
 * // Creating Result values
 * Result<ValidationError, Order> success = Result.success(order);
 * Result<ValidationError, Order> failure = Result.error(new ValidationError("Invalid order"));
 *
 * // Pattern matching style
 * String message = result.fold(
 *     error -> "Failed: " + error.getMessage(),
 *     order -> "Success: " + order.getId()
 * );
 *
 * // Chaining operations
 * Result<Error, ProcessedOrder> processed = validateOrder(data)
 *     .flatMapSuccess(order -> enrichOrder(order))
 *     .flatMapSuccess(enriched -> persistOrder(enriched));
 *
 * // Conditional execution
 * result.ifSuccess(order -> processOrder(order));
 * result.ifError(error -> logError(error));
 *
 * // Mapping
 * Result<Error, String> mapped = result.mapSuccess(order -> order.getId());
 * }</pre>
 *
 * @param <ERROR> the error type (corresponds to {@link Either}'s T1)
 * @param <SUCCESS> the success type (corresponds to {@link Either}'s T2)
 * @see Either
 */
public class Result<ERROR, SUCCESS> extends Either<ERROR, SUCCESS> {

    /**
     * Construct a new {@link Result} with the given error and success values.
     * Only one can be non-null.
     *
     * @param error   the error value (or null if success)
     * @param success the success value (or null if error)
     * @throws IllegalArgumentException if both values are non-null or both are null
     */
    protected Result(ERROR error, SUCCESS success) {
        super(error, success);
    }

    /**
     * Create a successful {@link Result} with the given value.
     *
     * @param value the success value (must not be null)
     * @param <E>   the error type
     * @param <S>   the success type
     * @return a new Result representing success
     * @throws IllegalArgumentException if value is null
     */
    public static <E, S> Result<E, S> success(S value) {
        return new Result<>(null, requireNonNull(value, "No success value provided"));
    }

    /**
     * Create a failed {@link Result} with the given error.
     *
     * @param error the error value (must not be null)
     * @param <E>   the error type
     * @param <S>   the success type
     * @return a new Result representing failure
     * @throws IllegalArgumentException if error is null
     */
    public static <E, S> Result<E, S> error(E error) {
        return new Result<>(requireNonNull(error, "No error value provided"), null);
    }

    /**
     * Create a {@link Result} from an {@link Either}.
     *
     * @param either the Either to convert
     * @param <E>    the error type (Either's T1)
     * @param <S>    the success type (Either's T2)
     * @return a new Result with the same values
     */
    public static <E, S> Result<E, S> fromEither(Either<E, S> either) {
        requireNonNull(either, "No either value provided");
        return new Result<>(either._1, either._2);
    }

    // ==================== Success/Error Accessors ====================

    /**
     * Check if this Result represents a success.
     *
     * @return true if this Result contains a success value
     * @see #isError()
     * @see Either#is_2()
     */
    public boolean isSuccess() {
        return is_2();
    }

    /**
     * Check if this Result represents an error.
     *
     * @return true if this Result contains an error value
     * @see #isSuccess()
     * @see Either#is_1()
     */
    public boolean isError() {
        return is_1();
    }

    /**
     * Get the success value (can be null if this is an error).
     *
     * @return the success value or null
     * @see #getSuccess()
     * @see #isSuccess()
     * @see Either#_2()
     */
    public SUCCESS success() {
        return _2();
    }

    /**
     * Get the error value (can be null if this is a success).
     *
     * @return the error value or null
     * @see #getError()
     * @see #isError()
     * @see Either#_1()
     */
    public ERROR error() {
        return _1();
    }

    /**
     * Get the success value wrapped as an {@link Optional}.
     *
     * @return Optional containing the success value, or empty if this is an error
     * @see #success()
     * @see Either#get_2()
     */
    public Optional<SUCCESS> getSuccess() {
        return get_2();
    }

    /**
     * Get the error value wrapped as an {@link Optional}.
     *
     * @return Optional containing the error value, or empty if this is a success
     * @see #error()
     * @see Either#get_1()
     */
    public Optional<ERROR> getError() {
        return get_1();
    }

    // ==================== Conditional Execution ====================

    /**
     * If this is a success, execute the given consumer with the success value.
     *
     * @param consumer the consumer to execute if this is a success
     * @see #ifError(Consumer)
     * @see Either#ifIs_2(Consumer)
     */
    public void ifSuccess(Consumer<SUCCESS> consumer) {
        ifIs_2(consumer);
    }

    /**
     * If this is an error, execute the given consumer with the error value.
     *
     * @param consumer the consumer to execute if this is an error
     * @see #ifSuccess(Consumer)
     * @see Either#ifIs_1(Consumer)
     */
    public void ifError(Consumer<ERROR> consumer) {
        ifIs_1(consumer);
    }

    // ==================== Mapping ====================

    /**
     * Map the success value using the given function.
     * If this is an error, the function is not applied and the error is preserved.
     *
     * @param mapper the function to apply to the success value
     * @param <S2>   the new success type
     * @return a new Result with the mapped success value, or the original error
     * @see #mapError(Function)
     */
    @SuppressWarnings("unchecked")
    public <S2> Result<ERROR, S2> mapSuccess(Function<? super SUCCESS, ? extends S2> mapper) {
        requireNonNull(mapper, "You must supply a mapper function");
        if (isError()) {
            return (Result<ERROR, S2>) this;
        }
        return Result.success(mapper.apply(success()));
    }

    /**
     * Map the error value using the given function.
     * If this is a success, the function is not applied and the success is preserved.
     *
     * @param mapper the function to apply to the error value
     * @param <E2>   the new error type
     * @return a new Result with the mapped error value, or the original success
     * @see #mapSuccess(Function)
     */
    @SuppressWarnings("unchecked")
    public <E2> Result<E2, SUCCESS> mapError(Function<? super ERROR, ? extends E2> mapper) {
        requireNonNull(mapper, "You must supply a mapper function");
        if (isSuccess()) {
            return (Result<E2, SUCCESS>) this;
        }
        return Result.error(mapper.apply(error()));
    }

    // ==================== FlatMapping ====================

    /**
     * FlatMap the success value using a function that returns a Result.
     * If this is an error, the function is not applied and the error is preserved.
     * <br>
     * This is useful for chaining operations that may fail:
     * <pre>{@code
     * Result<Error, ProcessedOrder> processed = validateOrder(data)
     *     .flatMapSuccess(order -> enrichOrder(order))
     *     .flatMapSuccess(enriched -> persistOrder(enriched));
     * }</pre>
     *
     * @param mapper the function to apply to the success value
     * @param <S2>   the new success type
     * @return the Result returned by the mapper, or the original error
     * @see #flatMapError(Function)
     */
    @SuppressWarnings("unchecked")
    public <S2> Result<ERROR, S2> flatMapSuccess(Function<? super SUCCESS, ? extends Result<ERROR, S2>> mapper) {
        requireNonNull(mapper, "You must supply a mapper function");
        if (isError()) {
            return (Result<ERROR, S2>) this;
        }
        return mapper.apply(success());
    }

    /**
     * FlatMap the error value using a function that returns a Result.
     * If this is a success, the function is not applied and the success is preserved.
     *
     * @param mapper the function to apply to the error value
     * @param <E2>   the new error type
     * @return the Result returned by the mapper, or the original success
     * @see #flatMapSuccess(Function)
     */
    @SuppressWarnings("unchecked")
    public <E2> Result<E2, SUCCESS> flatMapError(Function<? super ERROR, ? extends Result<E2, SUCCESS>> mapper) {
        requireNonNull(mapper, "You must supply a mapper function");
        if (isSuccess()) {
            return (Result<E2, SUCCESS>) this;
        }
        return mapper.apply(error());
    }

    // ==================== Fold ====================

    /**
     * Pattern matching style: applies one of the two functions depending on whether
     * this is an error or success, and returns the result.
     * <br>
     * <pre>{@code
     * String message = result.fold(
     *     error -> "Failed: " + error.getMessage(),
     *     order -> "Success: " + order.getId()
     * );
     * }</pre>
     *
     * @param errorMapper   the function to apply if this is an error
     * @param successMapper the function to apply if this is a success
     * @param <R>           the result type
     * @return the result of applying the appropriate function
     */
    @Override
    public <R> R fold(Function<? super ERROR, ? extends R> errorMapper,
                      Function<? super SUCCESS, ? extends R> successMapper) {
        return super.fold(errorMapper, successMapper);
    }

    // ==================== Swap ====================

    /**
     * Swap the error and success values, returning a new Result where
     * the success becomes the error and vice versa.
     *
     * @return a new Result with swapped values
     */
    @Override
    public Result<SUCCESS, ERROR> swap() {
        return new Result<>(_2, _1);
    }

    // ==================== Conversion ====================

    /**
     * Convert this Result to an {@link Either}.
     * Since Result extends Either, this simply returns this instance cast to Either.
     *
     * @return this Result as an Either
     */
    public Either<ERROR, SUCCESS> toEither() {
        return this;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        if (isSuccess()) {
            return "Result.success(" + success() + ")";
        } else {
            return "Result.error(" + error() + ")";
        }
    }
}
