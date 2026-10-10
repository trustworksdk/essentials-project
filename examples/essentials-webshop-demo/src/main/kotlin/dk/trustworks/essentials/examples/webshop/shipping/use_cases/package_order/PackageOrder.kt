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

package dk.trustworks.essentials.examples.webshop.shipping.use_cases.package_order

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.shipping.routing.ShippingOrderCommand

/**
 * Tell the warehouse to pack this order.
 *
 * This command is triggered by a **person** working the packaging list, not by an automation - which is exactly
 * the difference between this slice and `payment`'s hold. The event model draws both: a to-do view with a human
 * trigger, and a to-do view with an automated one.
 */
data class PackageOrder(override val id: OrderId) : ShippingOrderCommand
