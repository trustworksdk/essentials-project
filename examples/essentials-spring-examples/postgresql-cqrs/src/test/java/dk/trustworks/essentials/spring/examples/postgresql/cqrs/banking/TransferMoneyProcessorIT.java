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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking;

import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.Application;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.TestConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.request_intra_bank_money_transfer.RequestIntraBankMoneyTransfer;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.automations.transfer_money.TransferMoneyProcessor;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.request_intra_bank_money_transfer.RequestIntraBankMoneyTransferHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountNumber;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.IntraBankMoneyTransfers;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransferLifeCycleStatus;
import dk.trustworks.essentials.types.Amount;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@SpringBootTest(classes = {Application.class, TestConfiguration.class})
@Testcontainers
@DirtiesContext
public class TransferMoneyProcessorIT {
    private static final Logger log = LoggerFactory.getLogger(TransferMoneyProcessorIT.class);


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
    private IntraBankMoneyTransfers moneyTransfers;

    @Autowired
    private CommandBus commandBus;

    @Autowired
    private UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    @Autowired
    private DurableQueues durableQueues;

    // Injected so the test fails fast if either half of the split banking context is unwired:
    // the command slice's handler, and the automation that drives the transfer lifecycle.
    @Autowired
    private RequestIntraBankMoneyTransferHandler requestIntraBankMoneyTransferHandler;

    @Autowired
    private TransferMoneyProcessor transferMoneyProcessor;

    @Test
    void test_request_intrabank_money_transfer() {
        var account1Id                    = AccountId.random();
        var account1BalanceBeforeTransfer = Amount.of("100");
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var account1 = accounts.openNewAccount(account1Id,
                                                   AccountNumber.of("001123456"));
            account1.depositToday(account1BalanceBeforeTransfer, TransactionId.random());
        });

        var account2Id = AccountId.random();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> accounts.openNewAccount(account2Id,
                                                                                AccountNumber.of("9876541")));

        var transactionId  = TransactionId.random();
        var transferAmount = Amount.of("10");
        commandBus.sendAndDontWait(new RequestIntraBankMoneyTransfer(transactionId,
                                                                     account1Id,
                                                                     account2Id,
                                                                     transferAmount));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      var account2Balance = unitOfWorkFactory.withUnitOfWork(uow -> accounts.getAccount(account2Id).getBalance());
                      assertThat(account2Balance).isEqualTo(transferAmount);
                  });

        var account1Balance = unitOfWorkFactory.withUnitOfWork(uow -> accounts.getAccount(account1Id).getBalance());
        assertThat(account1Balance).isEqualTo(account1BalanceBeforeTransfer.subtract(transferAmount));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      var transferStatus = unitOfWorkFactory.withUnitOfWork(uow -> moneyTransfers.getTransfer(transactionId).getStatus());
                      assertThat(transferStatus).isEqualTo(TransferLifeCycleStatus.COMPLETED);
                  });

    }
}