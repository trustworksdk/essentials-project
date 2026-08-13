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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types;

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.types.CharSequenceType;

/**
 * Identifies a shipping order, and is the aggregate id of {@code ShippingOrder} -- so it is also the stream id its
 * events are written under.
 *
 * <p>A semantic type rather than a bare {@code String}, so it cannot be swapped with any other identifier by
 * mistake.
 *
 * <p>Strictly internal to this bounded context. An <em>order</em> belongs to the order-management system, not to
 * shipping, so the Kafka contracts on either side of {@code external_systems/order_management} carry a plain
 * {@code String} and the two adapters convert. Typing those contracts with this class would mean the
 * anti-corruption layer no longer translates.
 */
public class OrderId extends CharSequenceType<OrderId> {

    public OrderId(String value) {
        super(value);
    }
    public OrderId(CharSequence value) {
        super(value);
    }

    public static OrderId random() {
        return new OrderId(RandomIdGenerator.generate());
    }

    public static OrderId of(String id) {
        return new OrderId(id);
    }
}