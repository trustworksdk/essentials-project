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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.add_product

import dk.trustworks.essentials.examples.webshop.sales.routing.ProductCommand
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.types.Amount

/**
 * Put a product up for sale. Also the HTTP request body - there is no separate DTO, so there is no mapper to
 * keep in step with it.
 *
 * The caller supplies the [id]: a retried call addresses the same product instead of minting a second one, which
 * is what lets the decider be idempotent at all.
 */
data class AddProduct(
    override val id: ProductId,
    val name: String,
    val price: Amount
) : ProductCommand
