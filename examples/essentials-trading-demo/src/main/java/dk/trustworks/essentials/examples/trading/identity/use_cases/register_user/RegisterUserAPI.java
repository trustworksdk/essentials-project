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

package dk.trustworks.essentials.examples.trading.identity.use_cases.register_user;

import dk.trustworks.essentials.examples.trading.identity.types.EmailAlreadyRegisteredException;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of {@code identity.register_user}. Operator only (see {@code TradingDemoSecurityConfiguration}); customer
 * self-registration is a later phase.
 */
@RestController
@RequestMapping(path = "/api/admin/users")
public class RegisterUserAPI {
    private final CommandBus commandBus;

    public RegisterUserAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void registerUser(@RequestBody RegisterUser cmd) {
        commandBus.send(cmd);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<Map<String, Object>> emailTaken(EmailAlreadyRegisteredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "message", e.getMessage()));
    }
}
