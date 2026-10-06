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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.settle_trade;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The slice's door to the external clearing house, and a stub: it stands in for a blocking call by sleeping for the
 * configured latency and then confirming. Every clearing request is confirmed, so the automated trade lifecycle always
 * runs to the end; a real client would be a change to this class alone.
 */
@Component
public class ClearingHouseGateway {
    private static final Logger log = LoggerFactory.getLogger(ClearingHouseGateway.class);

    private final ClearingHouseProperties properties;

    public ClearingHouseGateway(ClearingHouseProperties properties) {
        this.properties = requireNonNull(properties, "No properties provided");
    }

    /**
     * Blocking call to the clearing house. Returns once it has confirmed, after the configured latency.
     *
     * @param settlementId the settlement whose clearing is confirmed
     */
    public void confirmClearing(SettlementId settlementId) {
        requireNonNull(settlementId, "No settlementId provided");
        log.debug("===> Asking the clearing house to confirm Settlement '{}', blocking for {}", settlementId, properties.getLatency());
        try {
            Thread.sleep(properties.getLatency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the clearing house", e);
        }
    }
}
