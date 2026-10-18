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

/**
 * A command the ledger refuses because it would break one of its invariants (design §3.4): not enough buying power,
 * withdrawable cash or sellable shares, a restricted account, a wrong currency, a closed generation. Answered
 * {@code 422}. Facts from outside — settlements, returns — are never refused this way.
 */
public class LedgerRuleViolation extends RuntimeException {
    public LedgerRuleViolation(String message) {
        super(message);
    }
}
