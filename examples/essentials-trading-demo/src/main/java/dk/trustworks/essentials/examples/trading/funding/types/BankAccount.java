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

package dk.trustworks.essentials.examples.trading.funding.types;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A customer's account at their bank: where a deposit comes from and a withdrawal goes to. The gateway validates the
 * IBAN's check digits; here it is only required to be there.
 */
public record BankAccount(String iban, String holderName) {
    public BankAccount {
        requireNonNull(iban, "No iban provided");
        requireNonNull(holderName, "No holderName provided");
        if (iban.isBlank() || holderName.isBlank()) {
            throw new FundingRuleViolation("A bank account needs an IBAN and a holder name");
        }
        iban = iban.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }
}
