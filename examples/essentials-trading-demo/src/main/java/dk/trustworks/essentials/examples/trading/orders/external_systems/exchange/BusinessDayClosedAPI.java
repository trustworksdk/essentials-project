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
package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import dk.trustworks.essentials.examples.trading.orders.use_cases.expire_order.ExpireOrder;
import dk.trustworks.essentials.examples.trading.orders.views.orders.OrdersQuery;
import org.slf4j.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only (under {@code /api/admin}): the end of a business day, standing in for the exchange's
 * {@code BusinessDayClosed} (SR-26) until the exchange-simulator adapter receives it. Every {@code DAY} order still open
 * is expired — one {@link ExpireOrder} per order, queued in {@link ExchangeReports}, so that a fill racing the expiry on
 * the same order is redelivered rather than lost.
 * <p>
 * A stopgap in two ways, both gone once the exchange expires its own {@code DAY} orders (SR-17): the open orders come
 * from the eventually consistent {@code orders} view, so an order placed a moment before the close may be missed and
 * stays open into the next day; and one placed after the exchange's cutoff but before this call is expired with the day
 * that just closed.
 */
@RestController
@RequestMapping("/api/admin/orders")
public class BusinessDayClosedAPI {
    private static final Logger log = LoggerFactory.getLogger(BusinessDayClosedAPI.class);

    private final OrdersQuery     orders;
    private final ExchangeReports reports;

    public BusinessDayClosedAPI(OrdersQuery orders, ExchangeReports reports) {
        this.orders = requireNonNull(orders, "No orders provided");
        this.reports = requireNonNull(reports, "No reports provided");
    }

    public record BusinessDayClosed(LocalDate businessDate) {
        public BusinessDayClosed {
            requireNonNull(businessDate, "No businessDate provided");
        }
    }

    /**
     * {@code 202}, with the number of orders queued for expiry; each is expired once the exchange confirms it cancelled
     * the order.
     */
    @PostMapping("/business-day-closed")
    public ResponseEntity<Map<String, Integer>> businessDayClosed(@RequestBody BusinessDayClosed closed) {
        var open = orders.openDayOrders();
        open.forEach(orderId -> reports.received(new ExpireOrder(orderId, closed.businessDate())));
        log.info("Business day {} closed: {} open DAY order(s) queued for expiry", closed.businessDate(), open.size());
        return ResponseEntity.accepted().body(Map.of("ordersToExpire", open.size()));
    }
}
