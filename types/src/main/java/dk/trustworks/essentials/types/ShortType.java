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

package dk.trustworks.essentials.types;

/**
 * Base class for all {@link SingleValueType}'s that encapsulate a {@link Short}.<br>
 * Example concrete implementation of the {@link ShortType}:
 * <pre>{@code
 * public class Count extends ShortType<Symbol> {
 *     public Count(short value) {
 *         super(value);
 *     }
 *
 *     public static Count of(short value) {
 *         return new Count(value);
 *     }
 *
 *     public static Count ofNullable(Short value) {
 *         return value != null ? new Count(value) : null;
 *     }
 * }
 * }</pre>
 *
 * @param <CONCRETE_TYPE> The concrete {@link ShortType} implementation
 */
public abstract class ShortType<CONCRETE_TYPE extends ShortType<CONCRETE_TYPE>> extends NumberType<Short, CONCRETE_TYPE> {

    public ShortType(Short value) {
        super(value);
    }

    public CONCRETE_TYPE increment() {
        return  (CONCRETE_TYPE) SingleValueType.from(this.getValue()+1, this.getClass());
    }

    public CONCRETE_TYPE decrement() {
        return  (CONCRETE_TYPE) SingleValueType.from(this.getValue()-1, this.getClass());
    }

    @Override
    public int compareTo(CONCRETE_TYPE o) {
        return value.compareTo(o.shortValue());
    }
}
