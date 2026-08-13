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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;

import java.time.ZoneId;

/**
 * Retune the trading-account closing-books policy, in one atomic change.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/trading-accounts/closing-books} -- there is no separate DTO to keep in step.
 *
 * <p><b>Every component is nullable and a {@code null} means "leave this one unchanged."</b> That is why this is the
 * one command in the context whose compact constructor does <em>not</em> {@code requireNonNull} its reference
 * components -- a partial update is the normal case, not a malformed request. The boxed {@code Long} and
 * {@code Integer} are boxed for exactly that reason: a primitive could not express "unchanged".
 *
 * @param mode           which built-in policy decides when the books roll, or {@code null} to leave it
 * @param eventThreshold how many events in a generation trigger a rollover, or {@code null} to leave it
 * @param timeBoundary   which calendar boundary triggers a rollover, or {@code null} to leave it
 * @param zoneId         the zone the time boundary is evaluated in, or {@code null} to leave it
 * @param intervalDays   the rollover interval in days, or {@code null} to leave it
 */
public record UpdateClosingBooksSettings(ClosingBooksDefaultPolicyType mode,
                                         Long eventThreshold,
                                         ClosingBooksTimeBoundary timeBoundary,
                                         ZoneId zoneId,
                                         Integer intervalDays) {
}
