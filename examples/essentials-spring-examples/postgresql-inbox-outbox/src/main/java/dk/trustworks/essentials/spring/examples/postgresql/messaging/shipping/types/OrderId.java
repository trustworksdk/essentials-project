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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types;

import dk.trustworks.essentials.types.CharSequenceType;
import jakarta.persistence.Embeddable;

import java.util.UUID;

/**
 * Identifies a shipping order.
 *
 * <p>A semantic type rather than a bare {@code String}, so it cannot be swapped with any other identifier by mistake.
 * It is the type used across the domain -- commands, events and the repository's lookup methods -- but note that
 * {@code ShippingOrder} stores its {@code @Id} as a plain {@code String}; see that class and the bounded context's
 * {@code CLAUDE.md} for why.
 *
 * <p>It is internal to this bounded context: the Kafka contracts on either side of
 * {@code external_systems/order_management} carry a {@code String}, and the two adapters convert.
 */
@Embeddable
public class OrderId extends CharSequenceType<OrderId> {
    /**
     * Required as otherwise JPA/Hibernate complains with "dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId has no persistent id property"
     * as it has problems with supporting SingleValueType immutable objects for identifier fields (as SingleValueType doesn't contain the necessary JPA annotations)
     */
    private String orderId;

    public OrderId(String value) {
        super(value);
        orderId = value.toString();
    }

    public OrderId(CharSequence value) {
        super(value);
        orderId = value.toString();
    }

    /**
     * Is required by JPA
     */
    protected OrderId() {
        super("null");
    }

    public static OrderId random() {
        return new OrderId(UUID.randomUUID().toString());
    }

    public static OrderId of(String id) {
        return new OrderId(id);
    }
}