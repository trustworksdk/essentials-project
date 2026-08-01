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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import dk.trustworks.essentials.components.foundation.json.EssentialsJacksonModules;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Selects the streaming WAL pre-filter matching the Jackson major the application uses.
 * <p>
 * The two implementations are behaviourally identical; they differ only in which Jackson streaming API they call. Use
 * this rather than naming either directly, so CDC wiring stays flavor-agnostic.
 *
 * @see DefaultWalMessageFilter
 * @see Jackson3WalMessageFilter
 */
public final class WalMessageFilters {

    private WalMessageFilters() {
    }

    /**
     * @param aggregateEventStreamTableNamesSupplier live supplier of the tracked event-stream table names, so
     *                                               aggregates registered at runtime are visible to filtering
     * @return the pre-filter for the active Jackson flavor
     */
    public static WalMessageFilter createForActiveJacksonFlavor(Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier) {
        return EssentialsJacksonModules.isJackson3Flavor()
               ? new Jackson3WalMessageFilter(aggregateEventStreamTableNamesSupplier)
               : new DefaultWalMessageFilter(aggregateEventStreamTableNamesSupplier);
    }
}
