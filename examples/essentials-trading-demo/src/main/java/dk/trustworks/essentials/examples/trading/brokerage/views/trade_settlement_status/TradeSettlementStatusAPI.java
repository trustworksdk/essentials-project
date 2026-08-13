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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code brokerage.trade_settlement_status} view slice, and of no other (§R2).
 * <p>
 * Two query methods, one slice: both interrogate {@code projection_trade_settlement}, the read model this slice owns.
 * <p>
 * There is no class-level {@code @RequestMapping}: the two endpoints sit under different admin prefixes and both paths
 * are load-bearing — the admin UI links to {@code /api/admin/projections/trade-settlements} by hand.
 * <p>
 * {@code @PathVariable SettlementId} binds because {@code config/TradingDemoWebConfiguration} imports
 * {@code EssentialsWebMvcConfigurer}. Without it this is an HTTP <b>500</b>, not a 400.
 */
@RestController
public class TradeSettlementStatusAPI {
    private final TradeSettlementStatusQuery query;

    public TradeSettlementStatusAPI(TradeSettlementStatusQuery tradeSettlementStatusQuery) {
        this.query = requireNonNull(tradeSettlementStatusQuery, "No tradeSettlementStatusQuery provided");
    }

    @GetMapping("/api/admin/projections/trade-settlements")
    public List<TradeSettlementStatus> tradeSettlements() {
        return query.tradeSettlements();
    }

    /**
     * The pre-slice version of this endpoint rehydrated the {@code Settlement} aggregate and would 500 on an unknown
     * id. Reading the projection makes the absent case ordinary, so it is a 404 — which also covers "created, not
     * projected yet", the asynchronous projection's own visible state.
     */
    @GetMapping("/api/admin/settlements/{settlementId}")
    public ResponseEntity<SettlementStatusView> settlement(@PathVariable SettlementId settlementId) {
        return query.findSettlement(settlementId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
