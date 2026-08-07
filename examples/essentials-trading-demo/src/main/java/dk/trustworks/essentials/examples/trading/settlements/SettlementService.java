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

package dk.trustworks.essentials.examples.trading.settlements;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Transactional application service for the {@link Settlement} aggregate.
 */
@Service
public class SettlementService {
    private final StatefulAggregateRepository<SettlementId, SettlementEvent, Settlement> repository;

    public SettlementService(StatefulAggregateRepository<SettlementId, SettlementEvent, Settlement> repository) {
        this.repository = repository;
    }

    @Transactional
    public Settlement createSettlement(SettlementId settlementId,
                                       String tradeId,
                                       String accountId,
                                       BigDecimal grossAmount) {
        return repository.save(new Settlement(settlementId, tradeId, accountId, grossAmount));
    }

    @Transactional
    public Settlement requestClearing(SettlementId settlementId) {
        var settlement = repository.load(settlementId);
        settlement.requestClearing();
        return settlement;
    }

    @Transactional
    public Settlement confirmClearing(SettlementId settlementId) {
        var settlement = repository.load(settlementId);
        settlement.confirmClearing();
        return settlement;
    }

    @Transactional
    public Settlement markSettled(SettlementId settlementId) {
        var settlement = repository.load(settlementId);
        settlement.markSettled();
        return settlement;
    }

    @Transactional
    public Settlement reconcile(SettlementId settlementId) {
        var settlement = repository.load(settlementId);
        settlement.reconcile();
        return settlement;
    }

    @Transactional
    public Settlement closeSettlement(SettlementId settlementId) {
        var settlement = repository.load(settlementId);
        settlement.closeSettlement();
        return settlement;
    }

    @Transactional(readOnly = true)
    public Settlement load(SettlementId settlementId) {
        return repository.load(settlementId);
    }

    @Transactional(readOnly = true)
    public Optional<Settlement> tryLoad(SettlementId settlementId) {
        return repository.tryLoad(settlementId);
    }
}
