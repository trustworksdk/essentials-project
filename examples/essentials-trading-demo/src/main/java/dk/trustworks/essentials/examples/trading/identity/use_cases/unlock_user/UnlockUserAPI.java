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

package dk.trustworks.essentials.examples.trading.identity.use_cases.unlock_user;

import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@RestController
@RequestMapping(path = "/api/admin/users")
public class UnlockUserAPI {
    private final CommandBus commandBus;

    public UnlockUserAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{userId}/unlock")
    public void unlock(@PathVariable UserId userId) {
        commandBus.send(new UnlockUser(userId));
    }
}
