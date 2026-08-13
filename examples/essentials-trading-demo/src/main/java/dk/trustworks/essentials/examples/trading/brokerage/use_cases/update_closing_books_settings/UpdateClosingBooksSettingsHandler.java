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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.update_closing_books_settings;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.update_closing_books_settings} slice -- one command, one
 * handler (rules/slice-design.md §R1).
 *
 * <p>Every requested field is folded into <b>one</b> {@code update} call, chaining {@code ClosingBooksSettings}'
 * {@code withX} copy methods. That is the whole point of the slice: {@code update} takes the policy's lock once and
 * swaps a single immutable reference, so no reader can ever observe a new {@code mode} against the old
 * {@code timeBoundary}, and no concurrent writer can interleave into a combination neither party asked for.
 *
 * <p>Do not split this into one call per field. That is what it replaced.
 */
@Service
public class UpdateClosingBooksSettingsHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(UpdateClosingBooksSettingsHandler.class);

    private final TradingAccountClosingBooksPolicy closingBooksPolicy;

    public UpdateClosingBooksSettingsHandler(TradingAccountClosingBooksPolicy closingBooksPolicy) {
        this.closingBooksPolicy = requireNonNull(closingBooksPolicy, "No closingBooksPolicy provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(UpdateClosingBooksSettings cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Updating closing-books settings: mode={}, eventThreshold={}, timeBoundary={}, zoneId={}, intervalDays={}",
                  cmd.mode(),
                  cmd.eventThreshold(),
                  cmd.timeBoundary(),
                  cmd.zoneId(),
                  cmd.intervalDays());
        closingBooksPolicy.update(settings -> {
            var updated = settings;
            if (cmd.mode() != null) {
                updated = updated.withMode(cmd.mode());
            }
            if (cmd.eventThreshold() != null) {
                updated = updated.withEventThreshold(cmd.eventThreshold());
            }
            if (cmd.timeBoundary() != null) {
                updated = updated.withTimeBoundary(cmd.timeBoundary());
            }
            if (cmd.zoneId() != null) {
                updated = updated.withZoneId(cmd.zoneId());
            }
            if (cmd.intervalDays() != null) {
                updated = updated.withIntervalDays(cmd.intervalDays());
            }
            return updated;
        });
    }
}
