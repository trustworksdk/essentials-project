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

import dk.trustworks.essentials.examples.webshop.sales.events.ProductAdded
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The trigger side of the slice: the UI's "add product" form, arriving over HTTP.
 *
 * The endpoint takes the command as its request body and hands it to the [CommandBus]. There is no service layer
 * in between, and no `@Transactional` here: the command bus opens the UnitOfWork, and the decider adapter appends
 * inside it.
 *
 * The response distinguishes a first call (201, the event was appended) from a retry (200, nothing happened) - the
 * decider's `null` surfacing all the way to the caller.
 */
@RestController
class AddProductAPI(private val commandBus: CommandBus) {

    @PostMapping("/api/products")
    fun addProduct(@RequestBody cmd: AddProduct): ResponseEntity<String> {
        val event: ProductAdded? = commandBus.send(cmd)
        return if (event != null) {
            ResponseEntity.status(201).body(event.id.toString())
        } else {
            ResponseEntity.ok(cmd.id.toString())
        }
    }
}
