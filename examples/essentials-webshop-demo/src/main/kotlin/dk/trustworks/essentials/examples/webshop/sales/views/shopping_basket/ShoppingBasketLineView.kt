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

package dk.trustworks.essentials.examples.webshop.sales.views.shopping_basket

import dk.trustworks.essentials.types.Amount
import dk.trustworks.essentials.examples.webshop.config.MoneyAttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * One row per (basket, product): what the basket page renders, already summed per line.
 *
 * The id is the two ids joined, because that is the grain of the row. [lastEventOrder] is the position of the
 * last basket event applied to this line, and the projection compares it before touching [quantity] - an
 * increment is not idempotent on its own, so without that comparison a redelivery would add the same unit twice.
 */
@Entity
@Table(name = "shopping_basket_line_view")
data class ShoppingBasketLineView(
    @Id
    @Column(name = "line_id")
    val id: String,

    @Column(name = "basket_id")
    val basketId: String,

    @Column(name = "product_id")
    val productId: String,

    var quantity: Int,

    @Convert(converter = MoneyAttributeConverter::class)
    @Column(precision = 19, scale = 2)
    var linePrice: Amount,

    var lastEventOrder: Long
) {
    companion object {
        fun lineId(basketId: String, productId: String): String = "$basketId#$productId"
    }
}

interface ShoppingBasketLineViewRepository : JpaRepository<ShoppingBasketLineView, String> {
    fun findByBasketId(basketId: String): List<ShoppingBasketLineView>

    fun deleteByBasketId(basketId: String)
}
