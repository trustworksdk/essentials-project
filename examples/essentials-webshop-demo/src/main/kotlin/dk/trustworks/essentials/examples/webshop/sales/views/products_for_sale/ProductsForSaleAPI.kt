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

package dk.trustworks.essentials.examples.webshop.sales.views.products_for_sale

import dk.trustworks.essentials.types.Amount
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The query side of the slice: what the shop page asks for when it renders the product list.
 *
 * The query does not go through any domain code, and there is nothing for it to go through - the row already has
 * the shape the caller wants. This is the point of the read model, and the reason a query needs no aggregate.
 */
@RestController
class ProductsForSaleAPI(private val repository: ProductsForSaleViewRepository) {

    data class ProductForSaleResponse(
        val productId: String,
        val name: String,
        val price: Amount
    )

    @GetMapping("/api/products-for-sale")
    fun productsForSale(): List<ProductForSaleResponse> =
        repository.findAll()
            .sortedBy { it.name }
            .map { ProductForSaleResponse(it.id, it.name, it.price) }
}
