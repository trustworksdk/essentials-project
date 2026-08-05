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

package dk.trustworks.essentials.examples.trading.instruments;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Transactional application service for the {@link Instrument} aggregate.
 */
@Service
public class InstrumentService {
    private final StatefulAggregateRepository<InstrumentId, InstrumentEvent, Instrument> repository;

    public InstrumentService(StatefulAggregateRepository<InstrumentId, InstrumentEvent, Instrument> repository) {
        this.repository = repository;
    }

    @Transactional
    public Instrument registerInstrument(InstrumentId instrumentId, String symbol, String displayName) {
        return repository.save(new Instrument(instrumentId, symbol, displayName));
    }

    @Transactional
    public Instrument rename(InstrumentId instrumentId, String displayName) {
        var instrument = repository.load(instrumentId);
        instrument.rename(displayName);
        return instrument;
    }

    @Transactional
    public Instrument suspend(InstrumentId instrumentId, String reason) {
        var instrument = repository.load(instrumentId);
        instrument.suspend(reason);
        return instrument;
    }

    @Transactional(readOnly = true)
    public Instrument load(InstrumentId instrumentId) {
        return repository.load(instrumentId);
    }

    @Transactional(readOnly = true)
    public Optional<Instrument> tryLoad(InstrumentId instrumentId) {
        return repository.tryLoad(instrumentId);
    }
}
