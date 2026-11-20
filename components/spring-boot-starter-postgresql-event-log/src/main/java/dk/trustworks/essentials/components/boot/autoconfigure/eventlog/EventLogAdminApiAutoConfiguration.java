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
                                                             ObjectProvider<EventLogInboxProcessor> inboxProcessors) {
        // A processor-owned subscription is reset through its processor's rebuild(), which clears the view (or purges the
        // queue) in the same transaction; the processors are looked up per call, since their subscriptions exist only once started
        return new DefaultEventLogSubscriptionsApi(securityProvider, eventLog, key -> {
            var owners = new ArrayList<Map.Entry<String, List<ViewProjection>>>();
            viewProcessors.orderedStream().forEach(p -> owners.add(Map.entry(p.getClass().getSimpleName(), p.projections())));
            partitionedViewProcessors.orderedStream().forEach(p -> owners.add(Map.entry(p.getClass().getSimpleName(), p.projections())));
            inboxProcessors.orderedStream().forEach(p -> owners.add(Map.entry(p.getClass().getSimpleName(), p.projections())));
            return owners.stream()
                         .filter(owner -> owner.getValue().stream().anyMatch(projection -> projection.key().equals(key)))
                         .findFirst()
                         .map(owner -> "it belongs to processor " + owner.getKey() + "; use its rebuild() so the view is cleared in the same transaction");
        });
    }
}
