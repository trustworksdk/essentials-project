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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.change_product_price

import dk.trustworks.essentials.examples.webshop.sales.events.ProductPriceChanged
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.reactive.command.CommandBus
import dk.trustworks.essentials.types.Amount
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The typed `@PathVariable ProductId` works because `EssentialsWebMvcConfigurer` is imported in
 * `config/WebshopDemoWebConfiguration` - without it this is an HTTP 500, not a 400.
 */
@RestController
class ChangeProductPriceAPI(private val commandBus: CommandBus) {

    data class NewPrice(val price: Amount)

    @PutMapping("/api/products/{productId}/price")
    fun changePrice(
        @PathVariable productId: ProductId,
        @RequestBody newPrice: NewPrice
    ): ResponseEntity<Void> {
        val event: ProductPriceChanged? = commandBus.send(ChangeProductPrice(productId, newPrice.price))
        // 200 means the price changed, 204 means it was already that price. The distinction is free here, and it
        // is the difference between "idempotent" and "pretends to have worked".
        return if (event != null) ResponseEntity.ok().build() else ResponseEntity.noContent().build()
    }
}
