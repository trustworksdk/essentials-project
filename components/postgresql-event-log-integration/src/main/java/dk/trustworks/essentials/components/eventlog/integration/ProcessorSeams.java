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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.*;

import java.sql.SQLException;
import java.util.*;

/**
 * <b>Test-only</b> seams of a processor: its mutants and how it subscribes (a test routes it through the engine's
 * package-private seams). Package-private; {@link #NONE} in normal use.
 */
record ProcessorSeams(Set<IntegrationMutant> mutants, Subscriber subscriber) {
    static final ProcessorSeams NONE = new ProcessorSeams(Set.of(), Subscriber.PUBLIC);

    ProcessorSeams {
        mutants = mutants.isEmpty() ? Set.of() : EnumSet.copyOf(mutants);
    }

    boolean has(IntegrationMutant mutant) {
        return mutants.contains(mutant);
    }

    /** How a processor subscribes */
    @FunctionalInterface
    interface Subscriber {
        Subscriber PUBLIC = (eventLog, definition, handler, source) -> switch (handler) {
            case PeriodicHandler periodic -> eventLog.subscribe(definition, periodic);
            case PageHandler page -> eventLog.subscribe(definition, page, source);
        };

        /** @param source the page source of a {@link PageHandler}; ignored for a {@link PeriodicHandler} */
        SubscriptionHandle subscribe(EventLog eventLog, SubscriptionDefinition definition, DeliveryHandler handler, PageTransactionSource source) throws SQLException;
    }
}
