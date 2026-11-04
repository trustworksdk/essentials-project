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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.examples.trading.identity.types.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.*;

/**
 * The users {@link DemoUsersSeeder} registers at startup ({@code trading-demo.identity.seed-users}), and the funded
 * brokerage account {@link DemoAccountsSeeder} opens for a user that has an {@code account} block. Demo values only.
 */
@ConfigurationProperties(prefix = "trading-demo.identity")
public class DemoUsersProperties {
    private List<SeedUser> seedUsers = new ArrayList<>();

    public List<SeedUser> getSeedUsers() {
        return seedUsers;
    }

    public void setSeedUsers(List<SeedUser> seedUsers) {
        this.seedUsers = seedUsers;
    }

    public static class SeedUser {
        private String    userId;
        private String    email;
        private String    displayName;
        private Set<Role> roles = new HashSet<>();
        private String    password;
        private SeedAccount account;

        /**
         * The account opened and funded for this user at startup, or {@code null} for none.
         */
        public SeedAccount getAccount() {
            return account;
        }

        public void setAccount(SeedAccount account) {
            this.account = account;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Set<Role> getRoles() {
            return roles;
        }

        public void setRoles(Set<Role> roles) {
            this.roles = roles;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * A brokerage account in USD with a linked bank account and one deposit of {@code deposit} USD from it.
     */
    public static class SeedAccount {
        private String     accountId;
        private String     iban;
        private String     holderName;
        private BigDecimal deposit;

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public String getIban() {
            return iban;
        }

        public void setIban(String iban) {
            this.iban = iban;
        }

        public String getHolderName() {
            return holderName;
        }

        public void setHolderName(String holderName) {
            this.holderName = holderName;
        }

        public BigDecimal getDeposit() {
            return deposit;
        }

        public void setDeposit(BigDecimal deposit) {
            this.deposit = deposit;
        }
    }
}
