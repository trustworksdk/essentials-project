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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.funding.types.BankAccount;
import dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account.LinkBankAccount;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_deposit.RequestDeposit;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.*;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Opens and funds the brokerage account configured for a demo user ({@code trading-demo.identity.seed-users[].account}),
 * so a customer can log in to the customer UI and trade at once on a fresh database. Runs after {@link DemoUsersSeeder}.
 * <p>
 * It goes through the same commands a customer's first visit does - open the account, link a bank account, request a
 * deposit - so the deposit travels the real payment flow: the payment-gateway simulator under the {@code simulators}
 * profile, the in-process stub gateway without it. The money is buying power once the deposit completes, and
 * withdrawable once it clears.
 * <p>
 * Safe on every startup, without reading anything:
 * <ul>
 *     <li>opening an account that exists is a no-op;</li>
 *     <li>the deposit has the fixed id {@code SEED-<accountId>}, and a deposit id that exists is a no-op for good. Not
 *     {@code CreditDeposit}, as the load generators use: the account remembers handled deposit ids only within one
 *     business day's generation, so a restart on a later day would credit it again;</li>
 *     <li>linking the bank account it already has is a no-op. One that the customer changed is linked back to the
 *     configured one.</li>
 * </ul>
 * A failure is logged and skipped - this is a convenience, not something the application needs to start.
 * <p>
 * On unless {@code trading-demo.identity.seed-accounts=false}; the tests turn it off
 * ({@code src/test/resources/config/application.yml}), as a funded account they did not open would change what they count.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.identity", name = "seed-accounts", havingValue = "true", matchIfMissing = true)
@Order(1)
public class DemoAccountsSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccountsSeeder.class);

    private final DemoUsersProperties properties;
    private final CommandBus          commandBus;

    public DemoAccountsSeeder(DemoUsersProperties properties, CommandBus commandBus) {
        this.properties = requireNonNull(properties, "No properties provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var user : properties.getSeedUsers()) {
            var account = user.getAccount();
            if (account == null) {
                continue;
            }
            var accountId = AccountId.of(account.getAccountId());
            try {
                commandBus.send(new OpenAccount(accountId, UserId.of(user.getUserId()), CurrencyCode.USD));
                commandBus.send(new LinkBankAccount(accountId, new BankAccount(account.getIban(),
                                                                               account.getHolderName() != null ? account.getHolderName() : user.getDisplayName())));
                commandBus.send(new RequestDeposit(DepositId.of("SEED-" + accountId), accountId, Money.of(account.getDeposit(), CurrencyCode.USD)));
                log.info("Demo account '{}' for <{}>: open, bank account linked, deposit SEED-{} of {} USD requested (no-ops where already done)",
                         accountId, user.getEmail(), accountId, account.getDeposit());
            } catch (RuntimeException e) {
                log.warn("Could not seed demo account '{}' for <{}>; skipped", accountId, user.getEmail(), e);
            }
        }
    }
}
