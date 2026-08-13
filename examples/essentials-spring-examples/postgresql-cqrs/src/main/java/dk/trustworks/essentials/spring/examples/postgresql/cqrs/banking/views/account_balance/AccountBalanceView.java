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

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;
import dk.trustworks.essentials.types.Amount;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The read model of the {@code banking.account_balance} view slice, owned by this slice alone.
 * <p>
 * Before this slice existed the only way to read a balance was to load the {@code Account} write aggregate
 * and call {@code getBalance()} — the read side served from the write model. This projects it instead.
 * <p>
 * Java entities extend {@link JavaVersionedEntity} rather than implementing {@code VersionedEntity} directly:
 * the bridge expresses the Kotlin {@code Version} value class in terms of two primitive {@code long}
 * accessors, so Java never constructs it.
 * <p>
 * Two things bite here:
 * <ul>
 *     <li>{@code version} initialises to {@link Version#NOT_SAVED_YET_VALUE} (-1), <strong>not</strong> 0 —
 *         0 means "saved at version zero" and fails the insert path;</li>
 *     <li>the field names {@code version} and {@code lastUpdated} are hardcoded in the reflection layer.
 *         Do not rename them, and keep them mutable.</li>
 * </ul>
 */
@DocumentEntity(tableName = "banking_account_balance")
public class AccountBalanceView extends JavaVersionedEntity<String, AccountBalanceView> {

    /**
     * Public, and it has to be. {@code EntityConfiguration} resolves the {@code @Id} property through Kotlin
     * reflection over {@code memberProperties}, which for a Java class synthesises the property from the
     * <em>field</em> — so reading it is a direct field access that throws {@code IllegalAccessException} on a
     * private field. {@code version} and {@code lastUpdated} escape this only because
     * {@link JavaVersionedEntity} declares them as Kotlin properties backed by real getter methods.
     */
    @Id
    public String accountId;

    @Indexed
    private String accountNumber;

    private Amount balance;

    private long           version     = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public AccountBalanceView() {
    }

    public AccountBalanceView(String accountId,
                              String accountNumber,
                              Amount balance) {
        this.accountId     = accountId;
        this.accountNumber = accountNumber;
        this.balance       = balance;
    }

    @Override
    public long getVersionValue() {
        return version;
    }

    @Override
    public void setVersionValue(long version) {
        this.version = version;
    }

    @Override
    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    @Override
    public void setLastUpdated(OffsetDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public Amount getBalance() {
        return balance;
    }

    public void setBalance(Amount balance) {
        this.balance = balance;
    }

    @Override
    public String toString() {
        return "AccountBalanceView(accountId=" + accountId + ", accountNumber=" + accountNumber + ", balance=" + balance + ")";
    }
}
