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

/**
 * Where a settlement has got to in its lifecycle, in the order it advances through.
 *
 * <p>The {@code Settlement} aggregate does not store this -- it stores the individual booleans each guard reads, and
 * this enum is how a projection or a read model names the state those booleans add up to. {@link #NONE} is the state
 * of a trade with no settlement yet.
 */
public enum SettlementStatus {
    NONE,
    CREATED,
    REQUESTED,
    CLEARING_REQUESTED,
    CLEARING_CONFIRMED,
    SETTLED,
    RECONCILED,
    CLOSED
}
