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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.suspend_instrument;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Suspend trading in an instrument, recording why.
 *
 * <p>Suspension is one-way in this demo -- there is no un-suspend event -- so the {@code reason} carried here is the
 * one that stands for the life of the stream. It is mandatory for that reason: a suspension with no explanation is
 * unreadable in the audit trail it will sit in forever.
 *
 * <p>Every component is mandatory, which is why {@code SuspendInstrumentAPI} takes the id as a {@code @PathVariable}
 * and the reason as a {@code @RequestParam} and builds this record itself.
 */
public record SuspendInstrument(InstrumentId instrumentId,
                                String reason) {
    public SuspendInstrument {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(reason, "No reason provided");
    }
}
