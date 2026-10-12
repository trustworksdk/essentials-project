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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import org.jdbi.v3.core.mapper.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The two queries this slice serves over {@code projection_instrument_details} — list, and lookup by id.
 * Both interrogate the same read model, so they are one slice (§R2).
 */
@Service
public class InstrumentDetailsQuery {
    private static final RowMapper<InstrumentDetails> ROW_MAPPER =
            (rs, ctx) -> toInstrumentDetails(rs.getString("instrument_id"),
                                             rs.getString("symbol"),
                                             rs.getString("display_name"),
                                             rs.getBoolean("suspended"),
                                             rs.getString("suspension_reason"),
                                             rs.getString("risk_status"),
                                             rs.getString("risk_detail"));

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public InstrumentDetailsQuery(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    public List<InstrumentDetails> instruments() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                    SELECT instrument_id, symbol, display_name, suspended, suspension_reason,
                                                                                           risk_status, risk_detail
                                                                                    FROM projection_instrument_details
                                                                                    ORDER BY instrument_id
                                                                                    """)
                                                                   .map(ROW_MAPPER)
                                                                   .list());
    }

    public Optional<InstrumentDetails> findInstrumentDetails(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                    SELECT instrument_id, symbol, display_name, suspended, suspension_reason,
                                                                                           risk_status, risk_detail
                                                                                    FROM projection_instrument_details
                                                                                    WHERE instrument_id = :instrumentId
                                                                                    """)
                                                                   .bind("instrumentId", instrumentId.toString())
                                                                   .map(ROW_MAPPER)
                                                                   .findOne());
    }

    private static InstrumentDetails toInstrumentDetails(String instrumentId,
                                                         String symbol,
                                                         String displayName,
                                                         boolean suspended,
                                                         String suspensionReason,
                                                         String riskStatus,
                                                         String riskDetail) {
        return new InstrumentDetails(InstrumentId.of(instrumentId),
                                     Symbol.of(symbol),
                                     displayName,
                                     suspended,
                                     suspensionReason,
                                     InstrumentRiskStatus.valueOf(riskStatus),
                                     riskDetail);
    }
}
