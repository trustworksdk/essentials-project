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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.external_systems.order_management.incoming;

/**
 * The one inbound event this boundary acts on: order-management has accepted an order, which is shipping's cue to
 * dispatch it. Every other {@code OrderEvent} is logged and dropped.
 */
public record OrderAccepted(String id, long orderNumber) implements OrderEvent {
}
