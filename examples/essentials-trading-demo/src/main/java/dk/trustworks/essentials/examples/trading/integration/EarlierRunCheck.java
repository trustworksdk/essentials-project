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

package dk.trustworks.essentials.examples.trading.integration;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Notices, once at startup, that the simulators remember an earlier run that this database does not: the database
 * was reset but a simulator's volume was not (a {@code docker compose down} without {@code -v}, or containers deleted
 * by hand). The catch-up readers then replay the simulators' whole event log into a database that knows none of it,
 * and every old order or payment is reported as one this brokerage never placed or requested - a WARN each.
 * <p>
 * The check runs before the receivers start ({@link ApplicationStartedEvent}, before {@code ApplicationReadyEvent}): a
 * database that has taken in no external event yet, against a simulator whose event log already holds this firm's
 * orders or any payment - which a fresh simulator only has once the brokerage has sent one. It then logs one WARN
 * saying what happened and how to reset, and {@link #simulatorsFromAnEarlierRun()} lets the handlers that report
 * unknown references log them at DEBUG instead.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class EarlierRunCheck {
    private static final Logger log = LoggerFactory.getLogger(EarlierRunCheck.class);

    private final JdbcTemplate        jdbcTemplate;
    private final SimulatorProperties properties;
    private final RestClient.Builder  restClientBuilder;
    private volatile boolean          simulatorsFromAnEarlierRun;

    public EarlierRunCheck(JdbcTemplate jdbcTemplate, SimulatorProperties properties, RestClient.Builder restClientBuilder) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.properties = requireNonNull(properties, "No properties provided");
        this.restClientBuilder = requireNonNull(restClientBuilder, "No restClientBuilder provided");
    }

    /**
     * @return whether the simulators hold orders or payments from a run this database never saw; their old events then
     * arrive as unknown references, expected and logged at DEBUG
     */
    public boolean simulatorsFromAnEarlierRun() {
        return simulatorsFromAnEarlierRun;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void check() {
        try {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM external_inbound_event)", Boolean.class))) {
                return; // this database has taken in events before: whatever the simulators hold, it has seen
            }
            var remembering = new ArrayList<String>();
            if (holdsEvents(properties.getExchange().getBaseUrl(), Map.of("eventType", "OrderAccepted", "participant", properties.getExchange().getParticipantId()))) {
                remembering.add("the exchange (orders)");
            }
            var gateway = properties.getPaymentGateway() != null ? properties.getPaymentGateway().getBaseUrl() : null;
            if (gateway != null && holdsEvents(gateway, Map.of("eventType", "PaymentPending"))) {
                remembering.add("the payment gateway (payments)");
            }
            if (!remembering.isEmpty()) {
                simulatorsFromAnEarlierRun = true;
                log.warn("This database is new, but {} remember{} an earlier run: their old events will be replayed and reported as unknown orders " +
                                 "and payments (logged at DEBUG from here on). Reset the database and the simulators together - ./reset-stack.sh, " +
                                 "or docker compose -p essentials-trading-demo --profile simulators --profile observability down -v",
                         String.join(" and ", remembering), remembering.size() == 1 ? "s" : "");
            }
        } catch (RuntimeException e) {
            log.debug("Could not check the simulators for an earlier run: {}", e.toString());
        }
    }

    private boolean holdsEvents(String baseUrl, Map<String, String> filter) {
        var client = restClientBuilder.clone().baseUrl(baseUrl).build();
        var events = client.get()
                           .uri(uri -> {
                               var builder = uri.path("/api/events").queryParam("after", 0).queryParam("limit", 1);
                               filter.forEach(builder::queryParam);
                               return builder.build();
                           })
                           .retrieve()
                           .body(JsonNode.class);
        return events != null && events.isArray() && !events.isEmpty();
    }
}
