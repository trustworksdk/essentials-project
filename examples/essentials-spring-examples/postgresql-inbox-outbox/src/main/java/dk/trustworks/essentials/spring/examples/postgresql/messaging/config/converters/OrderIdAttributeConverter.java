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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.config.converters;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.types.springdata.jpa.converters.BaseCharSequenceTypeAttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@code OrderId} to and from its {@code String} column form, so an entity could declare a field of that type
 * directly instead of a raw {@code String}.
 *
 * <p><strong>It currently applies to no field.</strong> {@code ShippingOrder}'s {@code @Id} is a plain {@code String}
 * because typing it as {@code OrderId} failed on an earlier JPA version -- see the bounded context's
 * {@code CLAUDE.md}. This converter is the machinery from that attempt, kept against a retry rather than deleted;
 * being {@code autoApply}, it will take effect the moment such a field appears.
 *
 * <p>The MongoDB sibling has no equivalent: Spring Data MongoDB handles the same job through
 * {@code AdditionalCharSequenceTypesSupported}, registered in its {@code Application}.
 */
@Converter(autoApply = true)
public class OrderIdAttributeConverter extends BaseCharSequenceTypeAttributeConverter<OrderId> {
    @Override
    protected Class<OrderId> getConcreteCharSequenceType() {
        return OrderId.class;
    }
}
