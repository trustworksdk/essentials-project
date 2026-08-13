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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.incoming;

/**
 * The order-management system's own event shape, as it arrives on the wire.
 *
 * <p>The identifier is a plain {@code String}, deliberately: this is the foreign contract, and it must not be stated
 * in terms of shipping's {@code OrderId}. Typing it as {@code OrderId} would mean the anti-corruption layer never
 * actually translates -- an upstream id-format change would then reach straight into the domain instead of stopping
 * at {@code OrderEventsKafkaListener}, which is the one place allowed to know both shapes.
 */
public interface OrderEvent {

    String id();
}
