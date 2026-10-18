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

package dk.trustworks.essentials.examples.trading.accounts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trading-demo.accounts.*} for the new ledger. (The frozen {@code brokerage} account's settings are under
 * {@code trading-demo.accounts.closing-books}, a separate class; the two do not overlap.)
 */
@ConfigurationProperties(prefix = "trading-demo.accounts")
public class AccountsProperties {
    /**
     * Business days a completed deposit stays uncleared — counted towards buying power but not withdrawable — because
     * the bank may still return it (design Q-7). Applied when a deposit is credited.
     */
    private int depositReturnWindowBusinessDays = 3;

    public int getDepositReturnWindowBusinessDays() {
        return depositReturnWindowBusinessDays;
    }

    public void setDepositReturnWindowBusinessDays(int depositReturnWindowBusinessDays) {
        this.depositReturnWindowBusinessDays = depositReturnWindowBusinessDays;
    }
}
