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

package dk.trustworks.essentials.examples.trading.prices;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Transactional application service for instrument prices.
 */
@Service
public class InstrumentPriceService {
    private final StatefulAggregateRepository<InstrumentId, InstrumentPriceEvent, InstrumentPrice> repository;

    public InstrumentPriceService(StatefulAggregateRepository<InstrumentId, InstrumentPriceEvent, InstrumentPrice> repository) {
        this.repository = repository;
    }

    @Transactional
    public InstrumentPrice initializePrice(InstrumentId instrumentId, BigDecimal price) {
        return repository.save(new InstrumentPrice(instrumentId, price));
    }

    @Transactional
    public InstrumentPrice updatePrice(InstrumentId instrumentId, BigDecimal price) {
        var instrumentPrice = repository.load(instrumentId);
        instrumentPrice.updatePrice(price);
        return instrumentPrice;
    }

    @Transactional(readOnly = true)
    public Optional<InstrumentPrice> tryLoad(InstrumentId instrumentId) {
        return repository.tryLoad(instrumentId);
    }
}
