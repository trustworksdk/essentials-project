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

package dk.trustworks.essentials.examples.trading.instruments;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Event hierarchy for the {@link Instrument} aggregate.
 */
public class InstrumentEvent {
    public final InstrumentId instrumentId;

    protected InstrumentEvent(InstrumentId instrumentId) {
        this.instrumentId = requireNonNull(instrumentId, "No instrumentId provided");
    }

    public static class InstrumentRegistered extends InstrumentEvent {
        public final String symbol;
        public final String displayName;

        @JsonCreator
        public InstrumentRegistered(@JsonProperty("instrumentId") InstrumentId instrumentId,
                                    @JsonProperty("symbol") String symbol,
                                    @JsonProperty("displayName") String displayName) {
            super(instrumentId);
            this.symbol = requireNonNull(symbol, "No symbol provided");
            this.displayName = requireNonNull(displayName, "No displayName provided");
        }
    }

    public static class InstrumentRenamed extends InstrumentEvent {
        public final String displayName;

        @JsonCreator
        public InstrumentRenamed(@JsonProperty("instrumentId") InstrumentId instrumentId,
                                 @JsonProperty("displayName") String displayName) {
            super(instrumentId);
            this.displayName = requireNonNull(displayName, "No displayName provided");
        }
    }

    public static class InstrumentSuspended extends InstrumentEvent {
        public final String reason;

        @JsonCreator
        public InstrumentSuspended(@JsonProperty("instrumentId") InstrumentId instrumentId,
                                   @JsonProperty("reason") String reason) {
            super(instrumentId);
            this.reason = requireNonNull(reason, "No reason provided");
        }
    }
}
