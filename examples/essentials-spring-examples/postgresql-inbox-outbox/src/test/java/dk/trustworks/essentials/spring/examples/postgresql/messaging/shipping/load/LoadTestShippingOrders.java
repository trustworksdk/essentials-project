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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.load;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities.ShippingOrder;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * The load-test harness's own query surface over the {@code ShippingOrder} table.
 * <p>
 * {@code findAllOrderIds()} used to sit on the production {@code entities/ShippingOrders} write repository, where it
 * was the only caller of a query that no command slice needed. It lives here now for the same reason a view slice
 * declares its own narrow interface: a repository that answers questions for one consumer belongs to that consumer.
 * The difference is that this consumer is a test, so the whole thing is test scope.
 */
public interface LoadTestShippingOrders extends Repository<ShippingOrder, String> {
    @Query("SELECT id FROM ShippingOrder")
    List<String> findAllOrderIds();

    List<ShippingOrder> saveAll(Iterable<ShippingOrder> orders);

    long count();
}
