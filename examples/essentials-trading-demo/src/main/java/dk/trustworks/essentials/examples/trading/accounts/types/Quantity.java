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

package dk.trustworks.essentials.examples.trading.accounts.types;

import dk.trustworks.essentials.types.LongType;

/**
 * A number of shares. Whole shares only: the exchange trades integer quantities (lot size 1 or more).
 */
public class Quantity extends LongType<Quantity> {
    public static final Quantity ZERO = new Quantity(0L);

    public Quantity(Long value) {
        super(value);
    }

    public static Quantity of(long value) {
        return new Quantity(value);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value() + other.value());
    }

    public Quantity minus(Quantity other) {
        return new Quantity(value() - other.value());
    }

    public Quantity min(Quantity other) {
        return value() <= other.value() ? this : other;
    }

    public boolean isPositive() {
        return value() > 0;
    }

    public boolean isZero() {
        return value() == 0;
    }

    public boolean isGreaterThan(Quantity other) {
        return value() > other.value();
    }
}
