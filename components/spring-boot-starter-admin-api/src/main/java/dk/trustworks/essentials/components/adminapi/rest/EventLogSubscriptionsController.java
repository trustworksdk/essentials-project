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

import dk.trustworks.essentials.components.adminapi.rest.dto.*;
import dk.trustworks.essentials.components.eventlog.ProgressKey;
import dk.trustworks.essentials.components.eventlog.processor.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link EventLogSubscriptionsApi}, implementing the contract's {@code event-log-subscriptions} tag:
 * the new event store engine's subscriptions, answered from the database by whichever instance receives the request.
 * <p>
 * A subscription is keyed by {@link ProgressKey} {@code (subscriber, aggregateType, instanceName)}. An exclusive
 * subscription's instance name is empty, which a path segment cannot carry, so it is the optional {@code instanceName}
 * query parameter, defaulting to the exclusive row. Only {@code deleteRetiredInstance}, which never applies to an
 * exclusive subscription, takes it as a path segment.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class EventLogSubscriptionsController {
    private static final String SUBSCRIPTION = "/event-log/subscriptions/{subscriber}/aggregate-types/{aggregateType}";

    private final EventLogSubscriptionsApi  eventLogSubscriptionsApi;
    private final AdminApiPrincipalResolver principalResolver;

    public EventLogSubscriptionsController(EventLogSubscriptionsApi eventLogSubscriptionsApi, AdminApiPrincipalResolver principalResolver) {
        this.eventLogSubscriptionsApi = requireNonNull(eventLogSubscriptionsApi, "No eventLogSubscriptionsApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/event-log/subscriptions")
    public List<ApiEventLogSubscription> getSubscriptions(@RequestParam(required = false) String aggregateType) {
        return eventLogSubscriptionsApi.getSubscriptions(principal(), Optional.ofNullable(aggregateType));
    }

    @GetMapping(SUBSCRIPTION)
    public ApiEventLogSubscription getSubscription(@PathVariable String subscriber,
                                                   @PathVariable String aggregateType,
                                                   @RequestParam(defaultValue = "") String instanceName) {
        var key = new ProgressKey(subscriber, aggregateType, instanceName);
        return eventLogSubscriptionsApi.getSubscription(principal(), key)
                                       .orElseThrow(() -> new AdminApiResourceNotFoundException("No event log subscription " + describe(key) + "."));
    }

    @GetMapping(SUBSCRIPTION + "/parked-events")
    public ApiParkedEventsPage getParkedEvents(@PathVariable String subscriber,
                                               @PathVariable String aggregateType,
                                               @RequestParam(defaultValue = "") String instanceName,
                                               @RequestParam(defaultValue = "0") long afterGlobalOrder,
                                               @RequestParam(defaultValue = "100") int limit) {
        return eventLogSubscriptionsApi.getParkedEvents(principal(), new ProgressKey(subscriber, aggregateType, instanceName), afterGlobalOrder, limit);
    }

    @PostMapping(SUBSCRIPTION + "/reset")
    public EventLogResetResult resetSubscription(@PathVariable String subscriber,
                                                 @PathVariable String aggregateType,
                                                 @RequestParam(defaultValue = "") String instanceName,
                                                 @RequestParam long resumeFrom) {
        return new EventLogResetResult(eventLogSubscriptionsApi.resetSubscription(principal(), new ProgressKey(subscriber, aggregateType, instanceName), resumeFrom));
    }

    @PostMapping(SUBSCRIPTION + "/parked-events/{globalOrder}/retry")
    public EventLogParkedRequestResult retryParkedEvent(@PathVariable String subscriber,
                                                        @PathVariable String aggregateType,
                                                        @PathVariable long globalOrder,
                                                        @RequestParam(defaultValue = "") String instanceName) {
        return new EventLogParkedRequestResult(eventLogSubscriptionsApi.retryParkedEvent(principal(), new ProgressKey(subscriber, aggregateType, instanceName), globalOrder));
    }

    @PostMapping(SUBSCRIPTION + "/parked-events/{globalOrder}/discard")
    public EventLogParkedRequestResult discardParkedEvent(@PathVariable String subscriber,
                                                          @PathVariable String aggregateType,
                                                          @PathVariable long globalOrder,
                                                          @RequestParam(defaultValue = "") String instanceName) {
        return new EventLogParkedRequestResult(eventLogSubscriptionsApi.discardParkedEvent(principal(), new ProgressKey(subscriber, aggregateType, instanceName), globalOrder));
    }

    @DeleteMapping(SUBSCRIPTION + "/instances/{instanceName}")
    public DeleteResult deleteRetiredInstance(@PathVariable String subscriber,
                                              @PathVariable String aggregateType,
                                              @PathVariable String instanceName) {
        return new DeleteResult(eventLogSubscriptionsApi.deleteRetiredInstance(principal(), new ProgressKey(subscriber, aggregateType, instanceName)));
    }

    private Object principal() {
        return principalResolver.requireAuthenticatedPrincipal();
    }

    private static String describe(ProgressKey key) {
        return "'" + key.subscriber() + "' of aggregate type '" + key.aggregateType() + "'"
                + (key.instanceName().isEmpty() ? " (exclusive)" : " for instance '" + key.instanceName() + "'");
    }
}
