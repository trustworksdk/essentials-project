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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.AccountNumber;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class AccountOpened extends AccountEvent {
    public final AccountNumber accountNumber;

    public AccountOpened(AccountId accountId,
                         AccountNumber accountNumber) {
        super(accountId);
        this.accountNumber = requireNonNull(accountNumber, "No accountNumber provided");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!super.equals(o)) return false;
        var that = (AccountOpened) o;
        return Objects.equals(accountNumber, that.accountNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), accountNumber);
    }

    @Override
    public String toString() {
        return "AccountOpened(accountId=" + accountId + ", accountNumber=" + accountNumber + ")";
    }
}
