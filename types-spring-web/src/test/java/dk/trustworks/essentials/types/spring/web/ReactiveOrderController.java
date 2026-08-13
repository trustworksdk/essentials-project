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

package dk.trustworks.essentials.types.spring.web;

import dk.trustworks.essentials.types.spring.web.model.CustomerId;
import dk.trustworks.essentials.types.spring.web.model.DueDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Endpoints for {@link EssentialsWebFluxConfigurerJackson3Test}, echoing each bound semantic type back as plain
 * text.
 * <p>
 * Plain text on purpose: the test is about {@code @PathVariable} <em>binding</em> and about the JSON codecs being
 * left alone, and a JSON response body would entangle the two.
 */
@RestController
public class ReactiveOrderController {

    @GetMapping("/reactive/customers/{customerId}")
    public Mono<String> byCustomerId(@PathVariable CustomerId customerId) {
        return Mono.just(customerId.toString());
    }

    @GetMapping("/reactive/orders/by-due-date/{dueDate}")
    public Mono<String> byDueDate(@PathVariable DueDate dueDate) {
        return Mono.just(dueDate.toString());
    }
}
