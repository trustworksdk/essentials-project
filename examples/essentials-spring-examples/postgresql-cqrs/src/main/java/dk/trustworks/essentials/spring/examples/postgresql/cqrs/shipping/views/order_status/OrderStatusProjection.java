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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.views.order_status;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.aggregates.ShippingOrders;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.OrderShipped;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.events.ShippingOrderRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projector for the {@code shipping.order_status} view slice — events in, read model out. A view slice never
 * produces events (rules/slice-design.md § The four slice kinds).
 * <p>
 * Every handler takes {@link OrderedMessage} as its second parameter: {@code message.getOrder()} is the
 * event's {@code EventOrder}, and comparing it against the stored version is what makes the projection
 * idempotent. That matters more here than usual — {@code ShipOrder} arrives over an at-least-once
 * {@code Inbox}, so {@code OrderShipped} redelivery is expected rather than exceptional.
 */
@Service
public class OrderStatusProjection extends ViewEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(OrderStatusProjection.class);

    private final DocumentDbRepository<OrderStatusView, String> repository;

    public OrderStatusProjection(ViewEventProcessorDependencies dependencies,
                                 DocumentDbRepository<OrderStatusView, String> orderStatusRepository) {
        super(dependencies);
        this.repository = requireNonNull(orderStatusRepository, "No orderStatusRepository provided");
    }

    @Override
    public String getProcessorName() {
        return "OrderStatusProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(ShippingOrders.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(ShippingOrderRegistered e, OrderedMessage message) {
        var id = e.orderId().toString();
        if (repository.findById(id) != null) {
            return;   // replay of the registration event
        }
        log.debug("===> Projecting ShippingOrderRegistered for '{}'", id);
        repository.save(new OrderStatusView(id,
                                            e.destinationAddress(),
                                            OrderStatusView.REGISTERED),
                        message.getOrder());
    }

    @MessageHandler
    void on(OrderShipped e, OrderedMessage message) {
        var id       = e.orderId().toString();
        var existing = repository.findById(id);
        if (existing == null) {
            // ShippingOrderRegistered is always the first event of the stream, so this can only mean the row
            // was wiped mid-replay. Skipping avoids inventing an order with no destination address.
            log.warn("No OrderStatusView for '{}' - skipping", id);
            return;
        }
        if (existing.getVersionValue() >= message.getOrder()) {
            return;   // already applied
        }
        log.debug("===> Projecting OrderShipped for '{}'", id);
        existing.setStatus(OrderStatusView.SHIPPED);
        repository.update(existing, message.getOrder());
    }

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly. Called once per
     * {@link AggregateType} this processor subscribes to, and there is only one.
     */
    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        log.info("Resetting OrderStatusView for '{}' from {}", aggregateType, resubscribeFromAndIncluding);
        repository.deleteAll();
    }
}
