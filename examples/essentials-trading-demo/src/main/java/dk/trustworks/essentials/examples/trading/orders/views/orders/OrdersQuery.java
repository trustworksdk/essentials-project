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

package dk.trustworks.essentials.examples.trading.orders.views.orders;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class OrdersQuery {
    private static final String SELECT = """
            SELECT order_id, account_id, instrument_id, side, type, quantity, limit_price, currency, time_in_force, status,
                   filled_quantity, average_fill_price, exchange_order_id, reason, placed_at, updated_at
            FROM projection_orders
            """;

    private static final RowMapper<OrderView> ROW = (rs, rowNum) -> {
        var limit        = rs.getBigDecimal("limit_price");
        var averagePrice = rs.getBigDecimal("average_fill_price");
        return new OrderView(OrderId.of(rs.getString("order_id")),
                             AccountId.of(rs.getString("account_id")),
                             InstrumentId.of(rs.getString("instrument_id")),
                             TradeSide.valueOf(rs.getString("side")),
                             OrderType.valueOf(rs.getString("type")),
                             Quantity.of(rs.getLong("quantity")),
                             limit == null ? null : Money.of(limit, CurrencyCode.of(rs.getString("currency"))),
                             TimeInForce.valueOf(rs.getString("time_in_force")),
                             OrderStatus.valueOf(rs.getString("status")),
                             Quantity.of(rs.getLong("filled_quantity")),
                             averagePrice == null ? null : Money.of(averagePrice, CurrencyCode.of(rs.getString("currency"))),
                             rs.getString("exchange_order_id") == null ? null : ExchangeOrderId.of(rs.getString("exchange_order_id")),
                             rs.getString("reason"),
                             rs.getObject("placed_at", OffsetDateTime.class),
                             rs.getObject("updated_at", OffsetDateTime.class));
    };

    private final JdbcTemplate jdbcTemplate;

    public OrdersQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    public List<OrderView> ordersOf(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return jdbcTemplate.query(SELECT + " WHERE account_id = ? ORDER BY placed_at DESC, order_id", ROW, accountId.toString());
    }

    /**
     * The {@code DAY} orders still live at the exchange, for the end-of-day expiry. Eventually consistent: an order
     * placed a moment ago may be missing.
     */
    public List<OrderId> openDayOrders() {
        return jdbcTemplate.queryForList("SELECT order_id FROM projection_orders WHERE time_in_force = ? AND status IN (?, ?, ?) ORDER BY order_id",
                                         String.class,
                                         TimeInForce.DAY.name(), OrderStatus.PENDING.name(), OrderStatus.OPEN.name(), OrderStatus.PARTIALLY_FILLED.name())
                           .stream()
                           .map(OrderId::of)
                           .toList();
    }

    public Optional<OrderView> findOrder(AccountId accountId, OrderId orderId) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(orderId, "No orderId provided");
        return jdbcTemplate.query(SELECT + " WHERE account_id = ? AND order_id = ?", ROW, accountId.toString(), orderId.toString()).stream().findFirst();
    }
}
