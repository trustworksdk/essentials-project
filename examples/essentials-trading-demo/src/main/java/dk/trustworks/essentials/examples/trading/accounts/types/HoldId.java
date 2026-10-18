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

import dk.trustworks.essentials.types.CharSequenceType;

/**
 * Names a cash hold. Chosen by the caller — an order id for an order's hold, a withdrawal id for a withdrawal's — so a
 * retried hold addresses the same hold.
 */
public class HoldId extends CharSequenceType<HoldId> {
    public HoldId(String value) {
        super(value);
    }

    public HoldId(CharSequence value) {
        super(value);
    }

    public static HoldId of(CharSequence value) {
        return new HoldId(value);
    }
}
