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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.register_shipping_order;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code shipping.register_shipping_order} slice (rules/slice-design.md §R2).
 * <p>
 * The route {@code POST /shipping/register-order} is unchanged from the single {@code ShippingAPI} controller
 * this slice was split out of — it is documented as a {@code curl} example in the module README. Sharing the
 * {@code /shipping} base path with the sibling {@code ship_order} slice is fine; only the full path+method
 * pair has to be unique.
 */
@RestController
@RequestMapping(path = "/shipping")
public class RegisterShippingOrderAPI {
    private final CommandBus commandBus;

    public RegisterShippingOrderAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/register-order")
    public void registerShippingOrder(@RequestBody RegisterShippingOrder cmd) {
        commandBus.sendAndDontWait(cmd);
    }
}
