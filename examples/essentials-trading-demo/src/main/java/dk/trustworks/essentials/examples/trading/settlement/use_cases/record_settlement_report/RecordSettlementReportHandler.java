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

package dk.trustworks.essentials.examples.trading.settlement.use_cases.record_settlement_report;

import dk.trustworks.essentials.examples.trading.settlement.aggregates.*;
import dk.trustworks.essentials.examples.trading.settlement.types.SettlementNotYetRecordable;
import dk.trustworks.essentials.examples.trading.settlement.views.fills.SettlementFillsProjection;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Records a settlement report on the execution's {@link TradeSettlement}, creating it with the first. The account,
 * instrument and side come from {@code settlement}'s own projection of the fill; a report for a fill not projected
 * yet is refused with {@link SettlementNotYetRecordable}, so the inbox redelivers it - the exchange reports a settlement
 * only after the fill, so it is a race, not an unknown execution.
 */
@Service
public class RecordSettlementReportHandler extends AnnotatedCommandHandler {
    private final TradeSettlements          settlements;
    private final SettlementFillsProjection fills;

    public RecordSettlementReportHandler(TradeSettlements settlements, SettlementFillsProjection fills) {
        this.settlements = requireNonNull(settlements, "No settlements provided");
        this.fills = requireNonNull(fills, "No fills provided");
    }

    @CmdHandler
    public void handle(RecordSettlementReport cmd) {
        requireNonNull(cmd, "No cmd provided");
        var settlement = settlements.find(cmd.executionId()).orElseGet(() -> {
            var fill = fills.fill(cmd.executionId())
                            .orElseThrow(() -> new SettlementNotYetRecordable("No fill projected yet for execution '" + cmd.executionId() + "'"));
            return settlements.saveNew(new TradeSettlement(cmd.executionId(), fill.accountId(), fill.instrumentId(), fill.side(),
                                                           cmd.kind() == RecordSettlementReport.Kind.CORRECTED ? cmd.previousAmount() : cmd.amount(),
                                                           cmd.intendedSettlementDate()));
        });
        switch (cmd.kind()) {
            case PENDING -> {
            }
            case DELAYED -> settlement.postpone(cmd.intendedSettlementDate(), cmd.attempt());
            case COMPLETED -> settlement.settle();
            case FAILED, CANCELLED, REJECTED -> settlement.abandon(cmd.kind().name(), cmd.reason());
            case CORRECTED -> settlement.correctAmount(cmd.previousAmount(), cmd.amount());
        }
    }
}
