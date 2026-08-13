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
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Repository wiring for this view slice's read model, and for no other.
 * <p>
 * {@code createForStringId(Class)} is the Java-friendly factory overload — it takes a {@code Class} rather
 * than a Kotlin {@code KClass}. Indexes are added once here at construction, never per query.
 */
@Configuration
public class AccountBalanceRepositoryConfiguration {

    @Bean
    public DocumentDbRepository<AccountBalanceView, String> accountBalanceRepository(DocumentDbRepositoryFactory factory) {
        var repository = factory.createForStringId(AccountBalanceView.class);
        repository.addIndexByPaths("banking_account_balance_account_number", "accountNumber");
        return repository;
    }
}
