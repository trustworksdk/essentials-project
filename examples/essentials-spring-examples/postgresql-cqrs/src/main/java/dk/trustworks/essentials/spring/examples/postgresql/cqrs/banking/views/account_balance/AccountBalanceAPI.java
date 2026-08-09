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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.views.account_balance;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code banking.account_balance} view slice, and of no other.
 * <p>
 * Two query methods, one slice: both interrogate the read model <em>this</em> slice owns, which is what §R2
 * scopes a view slice by. A query serving a different purpose over a different shape — a transfer history,
 * say — would be a different slice, not a third method here.
 * <p>
 * The read model is the response body (§R2). It is a projection built for exactly this query, so there is
 * nothing behind it to leak and no mapper to write.
 * <p>
 * Reads are eventually consistent: the projection is an asynchronous {@code ViewEventProcessor}, so a balance
 * fetched immediately after a transfer may still be the pre-transfer figure.
 */
@RestController
@RequestMapping(path = "/banking/accounts")
public class AccountBalanceAPI {
    private final DocumentDbRepository<AccountBalanceView, String> repository;

    public AccountBalanceAPI(DocumentDbRepository<AccountBalanceView, String> accountBalanceRepository) {
        this.repository = requireNonNull(accountBalanceRepository, "No accountBalanceRepository provided");
    }

    @GetMapping
    public List<AccountBalanceView> listAccountBalances() {
        return repository.findAll();
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountBalanceView> getAccountBalance(@PathVariable String accountId) {
        var view = repository.findById(accountId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }
}
