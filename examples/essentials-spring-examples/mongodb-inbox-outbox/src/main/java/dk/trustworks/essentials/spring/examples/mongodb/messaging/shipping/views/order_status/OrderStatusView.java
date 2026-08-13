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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.views.order_status;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.OrderId;

/**
 * The read model of the {@code shipping.order_status} slice - and its response body.
 * <p>
 * This is a Spring Data <em>closed interface projection</em>: Spring Data reads only the named properties out of the
 * {@code ShippingOrder} document and returns a proxy. It is a <strong>declaration, not a mapper</strong>, which is why
 * it satisfies §R2's no-adapter rule - there is no {@code …Response} mirror to keep in sync and no {@code toDto()}
 * step. It is also strictly better than returning the entity, which would hand the caller a mutable object and make
 * every field of the write model part of the wire contract.
 * <p>
 * Note what is <em>not</em> here: {@code destinationAddress}. The write model holds it; this read model has no reason
 * to expose it, and a closed projection means the query never even fetches it.
 */
public interface OrderStatusView {
    OrderId getId();

    boolean isShipped();
}
