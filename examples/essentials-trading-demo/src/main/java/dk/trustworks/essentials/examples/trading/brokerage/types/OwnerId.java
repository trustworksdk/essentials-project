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

/**
 * Identifies the party a trading account belongs to. Carried forward unchanged across every books generation.
 */
public class OwnerId extends CharSequenceType<OwnerId> {
    public OwnerId(String value) {
        super(value);
    }

    public OwnerId(CharSequence value) {
        super(value);
    }

    public static OwnerId of(CharSequence value) {
        return new OwnerId(value);
    }
}
