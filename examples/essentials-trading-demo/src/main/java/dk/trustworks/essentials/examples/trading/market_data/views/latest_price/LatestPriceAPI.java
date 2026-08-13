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

package dk.trustworks.essentials.examples.trading.market_data.views.latest_price;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * This slice's one API file. {@code InstrumentId} binds directly as a {@code @PathVariable} because
 * {@code config/TradingDemoWebConfiguration} imports {@code EssentialsWebMvcConfigurer} — without that import a
 * typed path variable fails as an HTTP 500 rather than a 400.
 */
@RestController
@RequestMapping("/api/admin/instrument-prices")
public class LatestPriceAPI {
    private final LatestPriceQuery query;

    public LatestPriceAPI(LatestPriceQuery query) {
        this.query = requireNonNull(query, "No query provided");
    }

    @GetMapping("/{instrumentId}")
    public ResponseEntity<LatestPrice> getLatestPrice(@PathVariable InstrumentId instrumentId) {
        return query.findLatestPrice(instrumentId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
