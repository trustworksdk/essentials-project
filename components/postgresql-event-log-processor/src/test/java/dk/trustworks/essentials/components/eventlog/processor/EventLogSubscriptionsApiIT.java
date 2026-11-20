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
import dk.trustworks.essentials.components.eventlog.processor.api.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The admin SPI: each operation maps onto {@link EventLogAdmin}, reads need the subscription
 * reader role, changes the writer role.
 */
class EventLogSubscriptionsApiIT extends AbstractProcessorIT {
    private static final long X = 37;

    /** A principal is the set of its role names */
    private static final EssentialsSecurityProvider ROLES = new EssentialsSecurityProvider() {
        @Override
        public boolean isAllowed(Object principal, String requiredRole) {
            return principal instanceof Set<?> roles && roles.contains(requiredRole);
        }

        @Override
        public Optional<String> getPrincipalName(Object principal) {
            return Optional.of(String.valueOf(principal));
        }
    };
    private static final Object READER = Set.of(EssentialsSecurityRoles.SUBSCRIPTION_READER.getRoleName());
    private static final Object WRITER = Set.of(EssentialsSecurityRoles.SUBSCRIPTION_READER.getRoleName(), EssentialsSecurityRoles.SUBSCRIPTION_WRITER.getRoleName());

    @Test
    void the_operations_map_onto_the_event_log_admin_under_the_subscription_roles() throws Exception {
        appendEvents(-1);
        var reactor = EventReactor.builder("notifier", ORDERS)
                                  .ownership(Ownership.perInstance("node-1"))
                                  .on(Added.class, event -> {
                                      if (event.globalOrder().longValue() == X) {
                                          throw new IllegalStateException("always fails");
                                      }
                                  })
                                  .errors(errors())
                                  .build(eventLog, serializer)
                                  .start();
        var api = new DefaultEventLogSubscriptionsApi(ROLES, eventLog);
        var key = reactor.key();
        try {
            awaitTrue("parked", Duration.ofSeconds(10), () -> api.getParkedEvents(READER, key, 0, 100).events().stream().anyMatch(event -> event.globalOrder() == X));

            var listed = api.getSubscriptions(READER, Optional.of(ORDERS));
            assertThat(listed).extracting(ApiEventLogSubscription::key).containsExactly(key);
            assertThat(listed.getFirst().progressModel()).isEqualTo("PERIODIC");
            assertThat(listed.getFirst().consistencyMode()).isEqualTo("FRESH");
            assertThat(api.getSubscription(READER, key)).isPresent();
            assertThat(api.getSubscription(READER, ProgressKey.exclusive("nobody", ORDERS))).isEmpty();

            assertThatThrownBy(() -> api.discardParkedEvent(READER, key, X)).isInstanceOf(EssentialsSecurityException.class);
            assertThatThrownBy(() -> api.getSubscriptions(Set.of(), Optional.empty())).isInstanceOf(EssentialsSecurityException.class);

            assertThat(api.retryParkedEvent(WRITER, key, X + 1)).isEqualTo(ParkedRequestResult.NOT_A_HEAD);
            assertThat(api.discardParkedEvent(WRITER, key, X)).isIn(ParkedRequestResult.DONE, ParkedRequestResult.ACCEPTED);
            awaitTrue("the head discarded and the held events released", Duration.ofSeconds(10), () -> api.getParkedEvents(READER, key, 0, 100).events().isEmpty());

            assertThat(api.resetSubscription(WRITER, ProgressKey.exclusive("nobody", ORDERS), 1)).isFalse();
            // A processor-owned subscription is refused and pointed at its processor's rebuild(), the rest are reset
            var guarded = new DefaultEventLogSubscriptionsApi(ROLES, eventLog, k -> k.equals(key) ? Optional.of("it belongs to a view processor") : Optional.empty());
            assertThatThrownBy(() -> guarded.resetSubscription(WRITER, key, 1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("view processor");
            assertThat(api.resetSubscription(WRITER, key, 1)).isTrue();
        } finally {
            reactor.stop();
        }
        assertThat(api.deleteRetiredInstance(WRITER, key)).isTrue();
        assertThat(api.getSubscription(READER, key)).isEmpty();
    }
}
