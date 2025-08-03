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

package dk.trustworks.essentials.shared.functional.tuple.comparable;

import dk.trustworks.essentials.shared.functional.QuadFunction;

import java.util.*;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a {@link ComparableTuple} with four elements.<br>
 * <b>Note</b>: {@link ComparableQuad} supports {@link #equals(Object)} comparison using subclasses, e.g.:
 * <pre>{@code
 *     public class LeftMiddleRightAndBottom extends ComparableQuad<String, String, String, String> {
 *
 *         public LeftMiddleRightAndBottom(String left, String middle, String right, String bottom) {
 *             super(left, middle, right, bottom);
 *         }
 *     }
 * }</pre>
 *
 * @param <T1> the first element type
 * @param <T2> the second element type
 * @param <T3> the third element type
 * @param <T4> the fourth element type
 */
public class ComparableQuad<T1 extends Comparable<? super T1>,
        T2 extends Comparable<? super T2>,
        T3 extends Comparable<? super T3>,
        T4 extends Comparable<? super T4>> implements ComparableTuple<ComparableQuad<T1, T2, T3, T4>> {
    /**
     * The first element in this tuple
     */
    public final T1 _1;
    /**
     * The second element in this tuple
     */
    public final T2 _2;

    /**
     * The third element in this tuple
     */
    public final T3 _3;

    /**
     * The fourth element in this tuple
     */
    public final T4 _4;

    /**
     * Construct a new {@link ComparableTuple} with 4 elements
     *
     * @param t1 the first element
     * @param t2 the second element
     * @param t3 the third element
     * @param t4 the fourth element
     */
    public ComparableQuad(T1 t1, T2 t2, T3 t3, T4 t4) {
        this._1 = t1;
        this._2 = t2;
        this._3 = t3;
        this._4 = t4;
    }

    @Override
    public int arity() {
        return 4;
    }

    @Override
    public List<?> toList() {
        return List.of(_1, _2, _3, _4);
    }

    /**
     * The first element in this tuple
     *
     * @return The first element in this tuple
     */
    public T1 _1() {
        return _1;
    }

    /**
     * The second element in this tuple
     *
     * @return The second element in this tuple
     */
    public T2 _2() {
        return _2;
    }

    /**
     * The third element in this tuple
     *
     * @return The third element in this tuple
     */
    public T3 _3() {
        return _3;
    }

    /**
     * The fourth element in this tuple
     *
     * @return The fourth element in this tuple
     */
    public T4 _4() {
        return _4;
    }

    @Override
    public int compareTo(ComparableQuad<T1, T2, T3, T4> o) {
        var t1Check = _1.compareTo(o._1);
        if (t1Check != 0) {
            return t1Check;
        }

        var t2Check = _2.compareTo(o._2);
        if (t2Check != 0) {
            return t2Check;
        }

        var t3Check = _3.compareTo(o._3);
        if (t3Check != 0) {
            return t3Check;
        }

        return _4.compareTo(o._4);
    }

    /**
     * Maps the elements of this {@link ComparableQuad} using the mapping function
     *
     * @param mappingFunction the mapping function
     * @param <R1>            result type for first element of the {@link ComparableQuad} after applying the mapping function
     * @param <R2>            result type for second element of the {@link ComparableQuad} after applying the mapping function
     * @param <R3>            result type for third element of the {@link ComparableQuad} after applying the mapping function
     * @param <R4>            result type for fourth element of the {@link ComparableQuad} after applying the mapping function
     * @return a new {@link ComparableQuad} with the result of applying the mapping function to this {@link ComparableQuad}
     */
    public <R1 extends Comparable<? super R1>,
            R2 extends Comparable<? super R2>,
            R3 extends Comparable<? super R3>,
            R4 extends Comparable<? super R4>> ComparableQuad<R1, R2, R3, R4> map(QuadFunction<? super T1, ? super T2, ? super T3, ? super T4, ComparableQuad<R1, R2, R3, R4>> mappingFunction) {
        requireNonNull(mappingFunction, "You must supply a mapping function");
        return mappingFunction.apply(_1, _2, _3, _4);
    }

    /**
     * Maps the elements of this {@link ComparableQuad} using four distinct mapping functions
     *
     * @param mappingFunction1 the mapping function for element number 1
     * @param mappingFunction2 the mapping function for element number 2
     * @param mappingFunction3 the mapping function for element number 3
     * @param mappingFunction4 the mapping function for element number 4
     * @param <R1>             result type for first element of the {@link ComparableQuad} after applying the mapping function
     * @param <R2>             result type for second element of the {@link ComparableQuad} after applying the mapping function
     * @param <R3>             result type for third element of the {@link ComparableQuad} after applying the mapping function
     * @param <R4>             result type for fourth element of the {@link ComparableQuad} after applying the mapping function
     * @return a new {@link ComparableQuad} with the result of applying the mapping function to this {@link ComparableQuad}
     */
    @SuppressWarnings("unchecked")
    public <R1 extends Comparable<? super R1>,
            R2 extends Comparable<? super R2>,
            R3 extends Comparable<? super R3>,
            R4 extends Comparable<? super R4>> ComparableQuad<R1, R2, R3, R4> map(Function<? super T1, ? super R1> mappingFunction1,
                                                                                  Function<? super T2, ? super R2> mappingFunction2,
                                                                                  Function<? super T3, ? super R3> mappingFunction3,
                                                                                  Function<? super T4, ? super R4> mappingFunction4) {
        requireNonNull(mappingFunction1, "You must supply a mappingFunction1");
        requireNonNull(mappingFunction2, "You must supply a mappingFunction2");
        requireNonNull(mappingFunction3, "You must supply a mappingFunction3");
        requireNonNull(mappingFunction4, "You must supply a mappingFunction4");
        R1 r1 = (R1) mappingFunction1.apply(_1);
        R2 r2 = (R2) mappingFunction2.apply(_2);
        R3 r3 = (R3) mappingFunction3.apply(_3);
        R4 r4 = (R4) mappingFunction4.apply(_4);
        return ComparableTuple.of(r1,
                                  r2,
                                  r3,
                                  r4);
    }

    /**
     * Map the <b>first</b> element of this {@link ComparableQuad} using the mapping function
     *
     * @param mappingFunction1 the mapping function for element number 1
     * @param <R1>             result type for first element of the {@link ComparableQuad} after applying the mapping function
     * @return a new {@link ComparableQuad} with the result of applying the mapping function to the first element of this {@link ComparableQuad}
     */
    @SuppressWarnings("unchecked")
    public <R1 extends Comparable<? super R1>> ComparableQuad<R1, T2, T3, T4> map1(Function<? super T1, ? super R1> mappingFunction1) {
        requireNonNull(mappingFunction1, "You must supply a mappingFunction1");
        R1 r1 = (R1) mappingFunction1.apply(_1);
        return ComparableTuple.of(r1,
                                  _2,
                                  _3,
                                  _4);
    }

    /**
     * Map the <b>second</b> element of this {@link ComparableQuad} using the mapping function
     *
     * @param mappingFunction2 the mapping function for element number 2
     * @param <R2>             result type for second element of the {@link ComparableQuad} after applying the mapping function
     * @return a new {@link ComparableQuad} with the result of applying the mapping function to the second element of this {@link ComparableQuad}
     */
    @SuppressWarnings("unchecked")
    public <R2 extends Comparable<? super R2>> ComparableQuad<T1, R2, T3, T4> map2(Function<? super T2, ? super R2> mappingFunction2) {
        requireNonNull(mappingFunction2, "You must supply a mappingFunction2");
        R2 r2 = (R2) mappingFunction2.apply(_2);
        return ComparableTuple.of(_1,
                                  r2,
                                  _3,
                                  _4);
    }

    /**
     * Map the <b>third</b> element of this {@link ComparableQuad} using the mapping function
     *
     * @param mappingFunction3 the mapping function for element number 3
     * @param <R3>             result type for third element of the {@link ComparableQuad} after applying the mapping function
     * @return a new {@link ComparableQuad} with the result of applying the mapping function to the third element of this {@link ComparableQuad}
     */
    @SuppressWarnings("unchecked")
    public <R3 extends Comparable<? super R3>> ComparableQuad<T1, T2, R3, T4> map3(Function<? super T3, ? super R3> mappingFunction3) {
        requireNonNull(mappingFunction3, "You must supply a mappingFunction3");
        R3 r3 = (R3) mappingFunction3.apply(_3);
        return ComparableTuple.of(_1,
                                  _2,
                                  r3,
                                  _4);
    }

    /**
     * Map the <b>fourth</b> element of this {@link ComparableQuad} using the mapping function
     *
     * @param mappingFunction4 the mapping function for element number 4
     * @param <R4>             result type for fourth element of the {@link ComparableQuad} after applying the mapping function
     * @return a new {@link ComparableQuad} with the result of applying the mapping function to the fourth element of this {@link ComparableQuad}
     */
    @SuppressWarnings("unchecked")
    public <R4 extends Comparable<? super R4>> ComparableQuad<T1, T2, T3, R4> map4(Function<? super T4, ? super R4> mappingFunction4) {
        requireNonNull(mappingFunction4, "You must supply a mappingFunction4");
        R4 r4 = (R4) mappingFunction4.apply(_4);
        return ComparableTuple.of(_1,
                                  _2,
                                  _3,
                                  r4);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ComparableQuad)) return false;
        var that = (ComparableQuad<?, ?, ?, ?>) o;
        return Objects.equals(_1, that._1) && Objects.equals(_2, that._2) && Objects.equals(_3, that._3) && Objects.equals(_4, that._4);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_1, _2, _3, _4);
    }

    @Override
    public String toString() {
        return "(" + _1 + ", " + _2 + ", " + _3 + ", " + _4 + ")";
    }
}