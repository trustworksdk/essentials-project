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
 * A trade's settlement as the exchange reports it: instructed (pending, possibly postponed), settled, or abandoned -
 * failed, cancelled or rejected, all final at the exchange (design {@code phase-4-settlement.md} §1).
 */
public enum SettlementState {
    INSTRUCTED,
    SETTLED,
    ABANDONED
}
