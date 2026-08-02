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

package dk.trustworks.essentials.examples.trading.prices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Event hierarchy for the {@link InstrumentPrice} aggregate.
 */
public class InstrumentPriceEvent {
    public final InstrumentId instrumentId;

    protected InstrumentPriceEvent(InstrumentId instrumentId) {
        this.instrumentId = requireNonNull(instrumentId, "No instrumentId provided");
    }

    public static class PriceInitialized extends InstrumentPriceEvent {
        public final BigDecimal price;

        @JsonCreator
        public PriceInitialized(@JsonProperty("instrumentId") InstrumentId instrumentId,
                                @JsonProperty("price") BigDecimal price) {
            super(instrumentId);
            this.price = requirePositive(price);
        }
    }

    public static class PriceUpdated extends InstrumentPriceEvent {
        public final BigDecimal price;

        @JsonCreator
        public PriceUpdated(@JsonProperty("instrumentId") InstrumentId instrumentId,
                            @JsonProperty("price") BigDecimal price) {
            super(instrumentId);
            this.price = requirePositive(price);
        }
    }

    private static BigDecimal requirePositive(BigDecimal price) {
        requireNonNull(price, "No price provided");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be > 0");
        }
        return price;
    }
}
