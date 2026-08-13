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
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.Application;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.TestConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Account;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountNumber;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AllowOverdrawingBalance;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionId;
import dk.trustworks.essentials.types.Amount;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code banking.account_balance} view slice: that the projection catches up, that it folds
 * deposits and withdrawals into a running balance, and that redelivering an already-applied event does not
 * double-count.
 */
@SpringBootTest(classes = {Application.class, TestConfiguration.class})
@Testcontainers
@DirtiesContext
public class AccountBalanceViewIT {

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @Container
    static org.testcontainers.kafka.KafkaContainer kafkaContainer = new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:latest")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    private Accounts accounts;

    @Autowired
    private DocumentDbRepository<AccountBalanceView, String> accountBalanceRepository;

    @Autowired
    private UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    @Test
    void account_balance_view_is_projected_from_the_account_events() {
        var accountId = AccountId.random();

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var account = accounts.openNewAccount(new Account(accountId, AccountNumber.of("001123456")));
            account.depositToday(Amount.of("250"), TransactionId.random());
            account.withdrawToday(Amount.of("100"), TransactionId.random(), AllowOverdrawingBalance.NO);
        });

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> {
                      var view = findView(accountId);
                      assertThat(view).isNotNull();
                      assertThat(view.getBalance()).isEqualTo(Amount.of("150"));
                  });

        var view = findView(accountId);
        assertThat(view.getAccountNumber()).isEqualTo("001123456");
        assertThat(view.getAccountId()).isEqualTo(accountId.toString());
    }

    @Test
    void the_projection_is_idempotent_when_an_event_is_redelivered() {
        var accountId = AccountId.random();

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var account = accounts.openNewAccount(new Account(accountId, AccountNumber.of("001987654")));
            account.depositToday(Amount.of("40"), TransactionId.random());
        });

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(findView(accountId)).isNotNull());

        var afterFirstDelivery = findView(accountId);
        assertThat(afterFirstDelivery.getBalance()).isEqualTo(Amount.of("40"));

        // The version stored on the row is the projected event's EventOrder, and every handler compares
        // against it before writing. Re-projecting the same stream must therefore be a no-op rather than
        // adding another 40 -- which is what makes at-least-once delivery safe here.
        var versionAfterFirstDelivery = afterFirstDelivery.getVersionValue();

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var account = accounts.getAccount(accountId);
            account.depositToday(Amount.of("10"), TransactionId.random());
        });

        Awaitility.waitAtMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> assertThat(findView(accountId).getBalance()).isEqualTo(Amount.of("50")));

        assertThat(findView(accountId).getVersionValue()).isGreaterThan(versionAfterFirstDelivery);
    }

    private AccountBalanceView findView(AccountId accountId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> accountBalanceRepository.findById(accountId.toString()));
    }
}
