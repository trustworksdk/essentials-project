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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.adapters.kafka.outgoing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.OrderId;

public class ExternalOrderShipped extends ExternalOrderShippingEvent {
    /**
     * The creator is stated explicitly because Jackson 3 reads a lone constructor as an implicit creator and treats a
     * single-argument one as *delegating* — the JSON object would then bind nothing and {@code orderId} would
     * deserialize to {@code null} without an error. {@code com.fasterxml.jackson.annotation} is the annotation package
     * both Jackson majors read, so this works on either flavour.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ExternalOrderShipped(@JsonProperty("orderId") OrderId orderId) {
        super(orderId);
    }
}
