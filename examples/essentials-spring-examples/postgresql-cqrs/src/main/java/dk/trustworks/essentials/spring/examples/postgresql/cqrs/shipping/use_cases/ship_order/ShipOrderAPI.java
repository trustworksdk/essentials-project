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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.use_cases.ship_order;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code shipping.ship_order} slice (rules/slice-design.md §R2).
 * <p>
 * The route {@code POST /shipping/ship-order} is unchanged from the single {@code ShippingAPI} controller this
 * slice was split out of — it is documented as a {@code curl} example in the module README.
 * <p>
 * Note this is <em>not</em> the only way a {@code ShipOrder} is issued: the {@code order_management}
 * translation slice raises the same command when an {@code OrderAccepted} arrives over Kafka. Two triggers,
 * one slice — the slice is the command, not the transport.
 */
@RestController
@RequestMapping(path = "/shipping")
public class ShipOrderAPI {
    private final CommandBus commandBus;

    public ShipOrderAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/ship-order")
    public void shipOrder(@RequestBody ShipOrder cmd) {
        commandBus.sendAndDontWait(cmd);
    }
}
