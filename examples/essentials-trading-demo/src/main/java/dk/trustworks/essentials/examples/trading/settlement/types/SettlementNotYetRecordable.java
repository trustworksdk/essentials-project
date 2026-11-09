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

package dk.trustworks.essentials.examples.trading.settlement.types;

/**
 * A settlement report that cannot be recorded yet: for an execution whose fill is not projected yet, or a correction
 * before the settlement it corrects (the exchange's deliveries are unordered, D11). Not an
 * {@code IllegalArgumentException}, so the inbox redelivers it instead of dead-lettering it.
 */
public class SettlementNotYetRecordable extends RuntimeException {
    public SettlementNotYetRecordable(String message) {
        super(message);
    }
}
