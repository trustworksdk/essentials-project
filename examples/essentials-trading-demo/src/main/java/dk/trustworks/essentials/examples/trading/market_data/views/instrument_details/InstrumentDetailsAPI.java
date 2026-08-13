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

package dk.trustworks.essentials.examples.trading.market_data.views.instrument_details;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * This slice's one API file, with two queries over the one read model it owns.
 * <p>
 * {@code InstrumentId} binds directly as a {@code @PathVariable} because {@code config/TradingDemoWebConfiguration}
 * imports {@code EssentialsWebMvcConfigurer}.
 */
@RestController
@RequestMapping("/api/admin/instruments")
public class InstrumentDetailsAPI {
    private final InstrumentDetailsQuery query;

    public InstrumentDetailsAPI(InstrumentDetailsQuery query) {
        this.query = requireNonNull(query, "No query provided");
    }

    @GetMapping
    public List<InstrumentDetails> listInstruments() {
        return query.instruments();
    }

    @GetMapping("/{instrumentId}")
    public ResponseEntity<InstrumentDetails> getInstrument(@PathVariable InstrumentId instrumentId) {
        return query.findInstrumentDetails(instrumentId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
