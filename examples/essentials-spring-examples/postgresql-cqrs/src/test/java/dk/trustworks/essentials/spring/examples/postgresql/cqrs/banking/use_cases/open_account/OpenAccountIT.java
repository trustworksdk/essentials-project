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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.open_account;

import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.Application;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.TestConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountNumber;
import dk.trustworks.essentials.types.Amount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static dk.trustworks.essentials.spring.examples.postgresql.cqrs.ExampleTestImages.*;

/**
 * Covers the {@code banking.open_account} slice: that the command opens an account with a zero balance, and that
 * re-sending it is a no-op rather than a second {@code AccountOpened}.
 */
@SpringBootTest(classes = {Application.class, TestConfiguration.class})
@Testcontainers
@DirtiesContext
public class OpenAccountIT {

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @Container
    static org.testcontainers.kafka.KafkaContainer kafkaContainer = new org.testcontainers.kafka.KafkaContainer(KAFKA_IMAGE)
            .withStartupAttempts(2);

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    private CommandBus commandBus;

    @Autowired
    private Accounts accounts;

    @Autowired
    private UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    @Test
    void an_account_is_opened_with_a_zero_balance() {
        var accountId = AccountId.random();

        commandBus.send(new OpenAccount(accountId, AccountNumber.of("001122334")));

        var account = unitOfWorkFactory.withUnitOfWork(uow -> accounts.getAccount(accountId));
        assertThat(account.getBalance()).isEqualTo(Amount.ZERO);
        assertThat((CharSequence) account.aggregateId()).isEqualTo(accountId);
    }

    @Test
    void opening_the_same_account_twice_is_a_no_op() {
        var accountId = AccountId.random();
        var command   = new OpenAccount(accountId, AccountNumber.of("001122335"));

        commandBus.send(command);
        var eventOrderAfterFirstOpen = unitOfWorkFactory.withUnitOfWork(
                uow -> accounts.getAccount(accountId).eventOrderOfLastRehydratedEvent());

        // At-least-once delivery on the command bus means this can genuinely happen.
        commandBus.send(command);

        var eventOrderAfterSecondOpen = unitOfWorkFactory.withUnitOfWork(
                uow -> accounts.getAccount(accountId).eventOrderOfLastRehydratedEvent());
        assertThat(eventOrderAfterSecondOpen).isEqualTo(eventOrderAfterFirstOpen);
    }
}
