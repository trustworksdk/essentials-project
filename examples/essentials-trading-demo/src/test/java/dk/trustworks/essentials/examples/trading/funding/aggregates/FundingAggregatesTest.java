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

package dk.trustworks.essentials.examples.trading.funding.aggregates;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * The funding rules on the aggregates alone (design {@code docs/design/phase-2-funding.md}).
 */
class FundingAggregatesTest {
    private static final AccountId   ACCOUNT    = AccountId.of("ACC-1");
    private static final BankAccount BANK       = new BankAccount("DK50 0040 0440 1162 43", "Alice Customer");
    private static final LocalDate   VALUE_DATE = LocalDate.of(2030, 1, 7);

    private static Money usd(String amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    @Test
    void a_bank_account_link_records_a_new_account_only() {
        var link = new BankAccountLink(ACCOUNT, BANK);
        link.link(new BankAccount("DK5000400440116243", "Alice Customer")); // the same account, written differently: nothing
        link.link(new BankAccount("GB82WEST12345698765432", "Alice Customer"));

        assertThat(link.getUncommittedChanges().events).hasSize(2);
        assertThat(link.bankAccount().iban()).isEqualTo("GB82WEST12345698765432");
    }

    @Test
    void a_deposit_lives_through_initiation_completion_and_return_once_each() {
        var deposit = new Deposit(DepositId.of("DEP-1"), ACCOUNT, usd("1000"), BANK);
        deposit.markInitiated(PaymentId.of("P-1"));
        deposit.markInitiated(PaymentId.of("P-1"));
        deposit.markCompleted(VALUE_DATE);
        deposit.markCompleted(VALUE_DATE);
        deposit.markFailed("REFUSED_BY_BANK"); // after the completion: nothing
        deposit.markReturned(VALUE_DATE.plusDays(2), "RETURNED_BY_BANK");
        deposit.markReturned(VALUE_DATE.plusDays(2), "RETURNED_BY_BANK");

        assertThat(deposit.getUncommittedChanges().events)
                .hasExactlyElementsOfTypes(DepositRequested.class, DepositInitiated.class, DepositCompleted.class, DepositReturnedByBank.class);
        assertThat(deposit.status()).isEqualTo(PaymentStatus.RETURNED);
    }

    @Test
    void a_completion_before_the_initiation_implies_it() {
        var withdrawal = new Withdrawal(WithdrawalId.of("WDR-1"), ACCOUNT, usd("300"), BANK);
        withdrawal.markCompleted(VALUE_DATE);
        withdrawal.markInitiated(PaymentId.of("P-1")); // late: nothing

        assertThat(withdrawal.getUncommittedChanges().events).hasExactlyElementsOfTypes(WithdrawalRequested.class, WithdrawalCompleted.class);
    }

    @Test
    void a_return_before_its_completion_waits_to_be_redelivered() {
        var deposit = new Deposit(DepositId.of("DEP-1"), ACCOUNT, usd("1000"), BANK);

        assertThatThrownBy(() -> deposit.markReturned(VALUE_DATE, "RETURNED_BY_BANK")).isInstanceOf(PaymentNotYetRecordable.class)
                                                                                     .isNotInstanceOf(IllegalArgumentException.class);
        deposit.markCompleted(VALUE_DATE);
        deposit.markReturned(VALUE_DATE.plusDays(1), "RETURNED_BY_BANK");
        assertThat(deposit.status()).isEqualTo(PaymentStatus.RETURNED);
    }

    @Test
    void a_payment_the_gateway_took_after_the_brokerage_gave_up_on_it_still_completes() {
        var deposit = new Deposit(DepositId.of("DEP-1"), ACCOUNT, usd("1000"), BANK);
        deposit.markFailed("GATEWAY_UNAVAILABLE");
        deposit.markCompleted(VALUE_DATE);

        assertThat(deposit.getUncommittedChanges().events).hasExactlyElementsOfTypes(DepositRequested.class, DepositFailed.class, DepositCompleted.class);
        assertThat(deposit.status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void a_payment_needs_a_positive_amount_and_a_complete_bank_account() {
        assertThatThrownBy(() -> new Deposit(DepositId.of("DEP-1"), ACCOUNT, usd("0"), BANK)).isInstanceOf(FundingRuleViolation.class);
        assertThatThrownBy(() -> new BankAccount(" ", "Alice")).isInstanceOf(FundingRuleViolation.class);
    }
}
