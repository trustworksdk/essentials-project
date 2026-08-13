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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_valuation;

import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code brokerage.trade_valuation} view slice, and of no other (§R2).
 * <p>
 * One query over the read model this slice owns. The response body <em>is</em> the read model — no DTO, no mapper.
 * <p>
 * {@code @PathVariable TradeId} binds because {@code config/TradingDemoWebConfiguration} imports
 * {@code EssentialsWebMvcConfigurer}. Without it this is an HTTP <b>500</b>, not a 400.
 */
@RestController
@RequestMapping(path = "/api/admin/trades")
public class TradeValuationAPI {
    private final TradeValuationQuery query;

    public TradeValuationAPI(TradeValuationQuery tradeValuationQuery) {
        this.query = requireNonNull(tradeValuationQuery, "No tradeValuationQuery provided");
    }

    /**
     * 404 covers both "no such trade" and "placed, not projected yet". The pre-slice version rehydrated the
     * {@code Trade} aggregate and could not express the second state — nor answer at all without reaching into
     * {@code market_data}'s write model for the price.
     */
    @GetMapping("/{tradeId}")
    public ResponseEntity<TradeValuation> tradeValuation(@PathVariable TradeId tradeId) {
        return query.findTradeValuation(tradeId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
