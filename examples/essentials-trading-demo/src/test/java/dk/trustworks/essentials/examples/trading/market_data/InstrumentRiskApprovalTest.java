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

package dk.trustworks.essentials.examples.trading.market_data;

import dk.trustworks.essentials.examples.trading.market_data.use_cases.risk_approve_instrument.InstrumentRiskApprovalProcessor;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument.RegisterInstrument;
import dk.trustworks.essentials.examples.trading.market_data.views.instrument_details.InstrumentRiskStatus;
import dk.trustworks.essentials.examples.trading.market_data.views.instrument_details.InstrumentDetailsQuery;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage of the {@code market_data.risk_approve_instrument} automation, which is the demo's example of a
 * {@code @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)} handler performing blocking I/O.
 *
 * <p>Registering an instrument is a command; the risk decision that follows is not. It arrives because
 * {@link InstrumentRiskApprovalProcessor} reacts to {@code InstrumentRegistered}, blocks on the risk service with no
 * {@code UnitOfWork} held, and then opens one of its own to record the answer. Both assertions therefore await: the
 * risk call takes the configured latency and the read model catches up after it.
 *
 * <p>That the blocking half runs with no {@code UnitOfWork} — the property the mode exists for — is pinned by
 * {@code NonTransactionalMessageHandlerIT} in {@code postgresql-event-store}, which can observe the handler's thread
 * from inside. This test covers what the demo can observe from outside: the decision lands, and which way it went.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.load.enabled=false",
        // Long enough that the handler genuinely blocks for a measurable stretch, short enough to keep the test quick.
        // Two orders of magnitude below the 30s DurableQueues message-handling timeout, so nothing is treated as stuck.
        "trading-demo.risk-approval.latency=1s",
        "trading-demo.risk-approval.rejected-symbols=RISKY"
})
class InstrumentRiskApprovalTest {
    /**
     * Generous for the same reason as {@code TradingDemoApplicationTest}'s: the automation and the projection both run
     * off event-store subscriptions over a durable-queue inbox behind a fenced lock, so catch-up time is a function of
     * polling intervals and lock hand-over rather than of the assertion. Every wait ends as soon as it holds.
     */
    private static final Duration DECISION_TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("trading-demo-risk-test-db")
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
    private CommandBus commandBus;

    @Autowired
    private InstrumentDetailsQuery instrumentDetailsQuery;

    @Test
    void a_registered_instrument_is_risk_approved_by_the_blocking_handler() {
        var instrumentId = InstrumentId.of("INST-RISK-APPROVE-1");

        commandBus.send(new RegisterInstrument(instrumentId, Symbol.of("SAFE"), "Safe Holdings"));

        await().atMost(DECISION_TIMEOUT).untilAsserted(() -> assertThat(instrumentDetailsQuery.findInstrumentDetails(instrumentId))
                .hasValueSatisfying(instrument -> {
                    assertThat(instrument.riskStatus()).isEqualTo(InstrumentRiskStatus.APPROVED);
                    // The awarded rating, which the stub derives from the symbol so a repeated call answers the same
                    assertThat(instrument.riskDetail()).isNotBlank();
                    // A risk decision is not a suspension, and recording one must not look like one
                    assertThat(instrument.suspended()).isFalse();
                }));
    }

    @Test
    void an_instrument_the_risk_service_refuses_is_recorded_as_rejected_with_its_reason() {
        var instrumentId = InstrumentId.of("INST-RISK-REJECT-1");

        commandBus.send(new RegisterInstrument(instrumentId, Symbol.of("RISKY"), "Risky Ventures"));

        await().atMost(DECISION_TIMEOUT).untilAsserted(() -> assertThat(instrumentDetailsQuery.findInstrumentDetails(instrumentId))
                .hasValueSatisfying(instrument -> {
                    assertThat(instrument.riskStatus()).isEqualTo(InstrumentRiskStatus.REJECTED);
                    assertThat(instrument.riskDetail()).contains("RISKY");
                    assertThat(instrument.suspended()).isFalse();
                }));
    }
}
