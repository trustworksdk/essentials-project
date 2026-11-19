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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What an {@link EventLogViewProcessor} or {@link EventLogReactor} is built with (design phase 10 §3.5). The Spring Boot
 * starter declares one as a bean; without Spring, build it from the started {@link EventLog}.
 *
 * @param eventLog         the started engine the processors subscribe to
 * @param serializer       the application's serializer, from {@code EssentialsJSONEventSerializers}
 * @param pageTransactions where an {@link EventLogViewProcessor}'s page transactions come from - {@link UnitOfWorkPageTransactions}
 *                         or {@link SpringPageTransactions}, so a handler sees the page's unit of work or Spring transaction.
 *                         Not used by an {@link EventLogReactor}
 */
public record EventLogProcessorDependencies(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource pageTransactions) {
    public EventLogProcessorDependencies {
        requireNonNull(eventLog, "No eventLog provided");
        requireNonNull(serializer, "No serializer provided");
        requireNonNull(pageTransactions, "No pageTransactions provided");
    }
}
