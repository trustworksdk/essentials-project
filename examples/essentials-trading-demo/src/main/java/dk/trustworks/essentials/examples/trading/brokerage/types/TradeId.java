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

package dk.trustworks.essentials.examples.trading.brokerage.types;

import dk.trustworks.essentials.types.CharSequenceType;

import java.util.UUID;

/**
 * Identifier of a {@code Trade}, and the id its event stream is keyed on.
 */
public class TradeId extends CharSequenceType<TradeId> {
    public TradeId(String value) {
        super(value);
    }

    public TradeId(CharSequence value) {
        super(value);
    }

    public static TradeId random() {
        return new TradeId(UUID.randomUUID().toString());
    }

    public static TradeId of(CharSequence value) {
        return new TradeId(value);
    }
}
