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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.adminapi.rest.AdminApiPrincipalResolver;
import dk.trustworks.essentials.components.eventlog.EventLog;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventlog.processor.api.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Declares the {@link EventLogSubscriptionsApi} the admin API's event-log subscription endpoints are
 * {@code @ConditionalOnBean} of, when the admin API starter and an {@link EssentialsSecurityProvider} are present. Same
 * shape as the shard-owned queue starter's admin configuration.
 */
@AutoConfiguration(
        afterName = {
                // EssentialsSecurityProvider, which the Api bean below is @ConditionalOnBean of
                "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration",
                "dk.trustworks.essentials.components.boot.autoconfigure.eventlog.EventLogAutoConfiguration"},
        beforeName =
                // The admin API registers EventLogSubscriptionsController @ConditionalOnBean of the Api bean declared here
                "dk.trustworks.essentials.components.boot.autoconfigure.admin.api.EssentialsAdminApiAutoConfiguration")
@ConditionalOnClass({EventLog.class, RestController.class, AdminApiPrincipalResolver.class})
@ConditionalOnProperty(prefix = "essentials.event-log", name = "enabled", havingValue = "true")
public class EventLogAdminApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EssentialsSecurityProvider.class, EventLog.class})
    public EventLogSubscriptionsApi eventLogSubscriptionsApi(EssentialsSecurityProvider securityProvider, EventLog eventLog,
                                                             ObjectProvider<EventLogViewProcessor> viewProcessors,
                                                             ObjectProvider<EventLogPartitionedViewProcessor> partitionedViewProcessors,
                                                             ObjectProvider<EventLogInboxProcessor> inboxProcessors,
                                                             ObjectProvider<ViewProjection> viewProjections) {
        // A processor-owned subscription is reset through its processor's rebuild(), which clears the view (or purges the
        // queue) in the same transaction; the processors are looked up per call, since their subscriptions exist only once started
        // Only the processors running in this JVM are known: a key owned by a processor of another application is not refused
        return new DefaultEventLogSubscriptionsApi(securityProvider, eventLog, key -> {
            var owners = new ArrayList<Map.Entry<String, List<ViewProjection>>>();
            var rebuild = " has a rebuild(): use it, so the view is cleared (and any queue purged) in the same transaction as the reset";
            viewProcessors.orderedStream().forEach(p -> owners.add(Map.entry("view processor " + p.getClass().getSimpleName() + rebuild, p.projections())));
            partitionedViewProcessors.orderedStream().forEach(p -> owners.add(Map.entry("partitioned view processor " + p.getClass().getSimpleName() + rebuild, p.projections())));
            inboxProcessors.orderedStream().forEach(p -> owners.add(Map.entry("inbox processor " + p.getClass().getSimpleName() + " has no rebuild(): a reset would forward every event from the " +
                                                                                      "resume point to its handlers again; if that is intended, call EventLog.admin().reset(key, from) from the application", p.projections())));
            // A ViewProjection bean of its own is a view too: a reset would re-apply events on top of the rows it already holds
            viewProjections.orderedStream().forEach(p -> owners.add(Map.entry("view projection " + p.key().subscriber() + " (a ViewProjection bean), which has a " +
                                                                                      "rebuild(truncate): use it, so the view is cleared in the same transaction as the reset", List.of(p))));
            return owners.stream()
                         .filter(owner -> owner.getValue().stream().anyMatch(projection -> projection.key().equals(key)))
                         .findFirst()
                         .map(owner -> "it belongs to the " + owner.getKey());
        });
    }
}
