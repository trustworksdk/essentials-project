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
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class PackageOrderAPI(private val commandBus: CommandBus) {

    @PostMapping("/api/shipping/orders/{orderId}/package")
    fun packageOrder(@PathVariable orderId: OrderId): ResponseEntity<Void> {
        val event: OrderPackagingRequested? = commandBus.send(PackageOrder(orderId))
        return if (event != null) ResponseEntity.accepted().build() else ResponseEntity.noContent().build()
    }
}
