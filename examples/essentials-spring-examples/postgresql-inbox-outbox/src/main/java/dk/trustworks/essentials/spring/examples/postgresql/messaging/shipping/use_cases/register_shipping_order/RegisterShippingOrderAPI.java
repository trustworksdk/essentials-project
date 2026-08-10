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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.register_shipping_order;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API of the {@code shipping.register_shipping_order} slice, and of no other: one command, one decision
 * component, therefore one endpoint (§R2).
 * <p>
 * {@link RegisterShippingOrder} <em>is</em> the request body - there is no {@code …Request} mirror and no mapper,
 * because the command already is the wire contract.
 */
@RestController
@RequestMapping(path = "/shipping/register-order")
public class RegisterShippingOrderAPI {
    private final CommandBus commandBus;

    public RegisterShippingOrderAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    public void registerShippingOrder(@RequestBody RegisterShippingOrder cmd) {
        commandBus.send(cmd);
    }
}
