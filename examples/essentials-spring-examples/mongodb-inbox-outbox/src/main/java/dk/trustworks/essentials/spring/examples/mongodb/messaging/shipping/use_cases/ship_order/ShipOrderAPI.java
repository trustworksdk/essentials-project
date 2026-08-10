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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.use_cases.ship_order;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API of the {@code shipping.ship_order} slice, and of no other: one command, one decision component, therefore
 * one endpoint (§R2).
 * <p>
 * This is only one of the slice's two triggers - {@code external_systems/order_management} raises the same command
 * when an {@code OrderAccepted} arrives over Kafka. Two triggers do not make two slices; a command slice is
 * identified by its command type, not by its transport.
 */
@RestController
@RequestMapping(path = "/shipping/ship-order")
public class ShipOrderAPI {
    private final CommandBus commandBus;

    public ShipOrderAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    public void shipOrder(@RequestBody ShipOrder cmd) {
        commandBus.send(cmd);
    }
}
