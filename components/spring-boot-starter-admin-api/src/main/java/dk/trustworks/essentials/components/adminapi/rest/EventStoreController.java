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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.adminapi.rest.dto.GlobalEventOrderResult;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link EventStoreApi}, implementing the contract's {@code event-store} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class EventStoreController {

    private final EventStoreApi             eventStoreApi;
    private final AdminApiPrincipalResolver principalResolver;

    public EventStoreController(EventStoreApi eventStoreApi, AdminApiPrincipalResolver principalResolver) {
        this.eventStoreApi = requireNonNull(eventStoreApi, "No eventStoreApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/event-store/aggregate-types/{aggregateType}/highest-global-event-order")
    public GlobalEventOrderResult findHighestGlobalEventOrderPersisted(@PathVariable String aggregateType) {
        return eventStoreApi.findHighestGlobalEventOrderPersisted(principalResolver.requireAuthenticatedPrincipal(),
                                                                 AggregateType.of(aggregateType))
                            .map(globalEventOrder -> new GlobalEventOrderResult(globalEventOrder.longValue()))
                            .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                    "No events are persisted for aggregate type '" + aggregateType + "'."));
    }

    @GetMapping("/event-store/subscriptions")
    public List<ApiSubscription> findAllSubscriptions() {
        return eventStoreApi.findAllSubscriptions(principalResolver.requireAuthenticatedPrincipal());
    }
}
