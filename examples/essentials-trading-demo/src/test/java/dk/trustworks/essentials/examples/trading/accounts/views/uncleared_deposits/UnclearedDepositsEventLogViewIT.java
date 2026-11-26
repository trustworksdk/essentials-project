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
package dk.trustworks.essentials.examples.trading.accounts.views.uncleared_deposits;

import dk.trustworks.essentials.components.eventlog.processor.EventLogProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.events.DepositCredited;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit.CreditDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.config.TradingDemoEventLogViewProcessor;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The UoW question of porting a view to the event-log engine: a demo view handler writes through the Spring
 * transaction-aware unit of work ({@code getRequiredUnitOfWork().handle()}, here {@code pageHandle()}). Under the
 * starter's {@code SpringPageTransactions} that unit of work must join the engine's page transaction - same
 * connection, committed with the page - rather than open a transaction of its own.
 * <p>
 * Runs the demo on the {@code event-log} profile.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
})
@ActiveProfiles("event-log")
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class UnclearedDepositsEventLogViewIT {
    private static final AccountId ACCOUNT = AccountId.of("ACC-UOW-PROBE");
    private static final DepositId DEPOSIT = DepositId.of("DEP-UOW-PROBE");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-uncleared-deposits-event-log-it-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private CommandBus                  commandBus;
    @Autowired
    private JdbcTemplate                jdbcTemplate;
    @Autowired
    private UnclearedDepositsProjection unclearedDeposits;
    @Autowired
    private PageTransactionProbe        probe;

    @Test
    void a_credited_deposit_is_written_in_the_page_transaction_and_committed_with_it() {
        commandBus.send(new OpenAccount(ACCOUNT, UserId.of("USR-UOW-PROBE"), CurrencyCode.of("USD")));
        var businessDate = LocalDate.of(2030, 1, 7);
        commandBus.send(new CreditDeposit(ACCOUNT, DEPOSIT, Money.of("250", "USD"), businessDate));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForList("SELECT account_id FROM projection_uncleared_deposit WHERE deposit_id = ?", String.class, DEPOSIT.toString()))
                    .containsExactly(ACCOUNT.toString());
            assertThat(probe.observations).isNotEmpty();
        });
        assertThat(unclearedDeposits.clearingOnOrBefore(businessDate.plusYears(1)))
                .extracting(UnclearedDepositsProjection.UnclearedDeposit::depositId)
                .contains(DEPOSIT);
        // The handler's handle is the page's own connection, inside the page's Spring transaction
        assertThat(probe.observations).allSatisfy(observation -> {
            assertThat(observation.springTransactionActive()).as("Spring transaction active in the handler").isTrue();
            assertThat(observation.sameConnectionAsPage()).as("handler handle uses the page connection").isTrue();
            assertThat(observation.autoCommit()).as("handler handle is not auto-committing").isFalse();
        });
    }

    record Observation(boolean springTransactionActive, boolean sameConnectionAsPage, boolean autoCommit) {
    }

    /**
     * A second view on the same events that looks at the transaction its handler runs in.
     */
    static class PageTransactionProbe extends TradingDemoEventLogViewProcessor {
        final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
        private final DataSource                 dataSource;

        PageTransactionProbe(EventLogProcessorDependencies dependencies,
                             EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                             DataSource dataSource) {
            super("UnclearedDepositsPageTransactionProbe", List.of(AccountsAggregateTypes.ACCOUNTS), dependencies, unitOfWorkFactory);
            this.dataSource = dataSource;
        }

        @MessageHandler
        void handle(DepositCredited e) throws SQLException {
            var active      = TransactionSynchronizationManager.isActualTransactionActive();
            var handle      = pageHandle();
            var pageConnection = DataSourceUtils.getConnection(dataSource);
            observations.add(new Observation(active,
                                             handle.getConnection().unwrap(PGConnection.class) == pageConnection.unwrap(PGConnection.class),
                                             handle.getConnection().getAutoCommit()));
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        PageTransactionProbe pageTransactionProbe(EventLogProcessorDependencies dependencies,
                                                  EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                  DataSource dataSource) {
            return new PageTransactionProbe(dependencies, unitOfWorkFactory, dataSource);
        }
    }
}
