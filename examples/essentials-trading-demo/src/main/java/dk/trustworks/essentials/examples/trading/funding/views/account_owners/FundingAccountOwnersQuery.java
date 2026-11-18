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

package dk.trustworks.essentials.examples.trading.funding.views.account_owners;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Eventually consistent: an account opened a moment ago may not be known yet, and answers as somebody else's.
 */
@Service
public class FundingAccountOwnersQuery {
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public FundingAccountOwnersQuery(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    /**
     * An operator may move money on any account; a customer on their own.
     */
    public boolean mayFund(AccountId accountId, AuthenticatedUser caller) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(caller, "No caller provided");
        if (caller.isOperator()) {
            return true;
        }
        var owners = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("SELECT owner_id FROM projection_funding_account_owner WHERE account_id = :accountId")
                                                                .bind("accountId", accountId.toString())
                                                                .mapTo(String.class)
                                                                .list());
        return owners.contains(caller.userId().toString());
    }
}
