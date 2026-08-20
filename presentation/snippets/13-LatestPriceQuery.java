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

import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrice;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Answers "what is this instrument worth right now?" by reading the {@link InstrumentPrice} aggregate directly.
 *
 * <h2>Why this view has no projection</h2>
 * Every other view slice in this demo owns a projected read model, and that is the default. This one is a
 * deliberate, narrow exception, for two reasons:
 * <ul>
 *     <li><b>Strong consistency is the requirement, not an optimisation.</b> The demo's bootstrap decides whether
 *     to seed by asking whether a price already exists. A projection is eventually consistent, so on a restart
 *     against a populated database the probe could answer "absent" while the data is very much present — and the
 *     bootstrap would seed a second time on top of it.</li>
 *     <li><b>There is nothing to project.</b> A latest-price read model would be a single column keyed by the same
 *     id as the stream, rebuilt from the same two events, and always one hop behind. Projecting it would add a
 *     table and a subscription to reproduce the aggregate's own state exactly.</li>
 * </ul>
 * This is why {@link InstrumentPrice#latestPrice()} is the one public accessor on any aggregate in this module.
 * It is not the read side leaking out of the write model in general — it is this slice, with this reason, and the
 * moment a caller wants price <em>history</em> or prices <em>across</em> instruments, that is a different read
 * model and a different slice.
 * <p>
 * See {@code REFACTORING_PLAN.md} § Open questions.
 */
@Service
public class LatestPriceQuery {
    private final InstrumentPrices instrumentPrices;

    public LatestPriceQuery(InstrumentPrices instrumentPrices) {
        this.instrumentPrices = requireNonNull(instrumentPrices, "No instrumentPrices provided");
    }

    @Transactional(readOnly = true)
    public Optional<LatestPrice> findLatestPrice(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return instrumentPrices.findPrice(instrumentId)
                               .map(instrumentPrice -> new LatestPrice(instrumentId,
                                                                       instrumentPrice.latestPrice()));
    }
}
