/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dk.trustworks.essentials.examples.trading.funding.external_systems.payment_gateway;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.accounts.views.uncleared_deposits.UnclearedDepositsProjection;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account.LinkBankAccount;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_deposit.RequestDeposit;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The reaction path on the event-log engine, end to end: a requested deposit is read from the event log by
 * {@link PaymentRouterEventLogReaction}'s forwarder, put into the {@code PaymentRouter} inbox, and handled there - the
 * payment gateway is called. The (stub) gateway completes the payment at once, so the deposit then travels on through
 * {@code FundingLedgerEventLogReaction} (credits the account) into the uncleared-deposits view.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
})
@ActiveProfiles("event-log")
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class PaymentRouterEventLogReactionIT {
    private static final Duration  TIMEOUT = Duration.ofSeconds(60);
    private static final AccountId ACCOUNT = AccountId.of("ACC-PAY-ROUTER");
    private static final DepositId DEPOSIT = DepositId.of("DEP-PAY-ROUTER");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-payment-router-event-log-it-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @MockitoSpyBean
    private PaymentGateway              gateway;
    @Autowired
    private CommandBus                  commandBus;
    @Autowired
    private UnclearedDepositsProjection unclearedDeposits;
    @Autowired
    private ApplicationContext          context;

    @Test
    void a_requested_deposit_reaches_the_payment_gateway_through_the_event_log_reaction() {
        assertThat(context.getBeansOfType(PaymentRouterClassicReaction.class)).as("classic reaction on the event-log engine").isEmpty();
        assertThat(context.getBean(PaymentRouterEventLogReaction.class).isStarted()).isTrue();

        commandBus.send(new OpenAccount(ACCOUNT, UserId.of("USR-PAY-ROUTER"), CurrencyCode.of("USD")));
        commandBus.send(new LinkBankAccount(ACCOUNT, new BankAccount("GB82WEST12345698765432", "Alice Customer")));
        commandBus.send(new RequestDeposit(DEPOSIT, ACCOUNT, Money.of("250", "USD")));

        await().atMost(TIMEOUT).untilAsserted(() -> verify(gateway).initiate(eq(PaymentKind.DEPOSIT), eq(DEPOSIT.toString()), eq(ACCOUNT), eq(Money.of("250", "USD")), any()));
        // The stub completed the payment: FundingLedgerProcessor credited the deposit, which the view now holds uncleared
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(unclearedDeposits.clearingOnOrBefore(LocalDate.now().plusYears(1)))
                .extracting(UnclearedDepositsProjection.UnclearedDeposit::depositId)
                .contains(DEPOSIT));
        verify(gateway, times(1)).initiate(any(), eq(DEPOSIT.toString()), any(), any(), any());
    }
}
