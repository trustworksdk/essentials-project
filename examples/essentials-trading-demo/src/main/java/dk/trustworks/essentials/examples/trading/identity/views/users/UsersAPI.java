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

package dk.trustworks.essentials.examples.trading.identity.views.users;

import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@RestController
@RequestMapping("/api/admin/users")
public class UsersAPI {
    private final UsersQuery query;

    public UsersAPI(UsersQuery query) {
        this.query = requireNonNull(query, "No query provided");
    }

    @GetMapping
    public List<UserSummary> listUsers() {
        return query.users();
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserSummary> getUser(@PathVariable UserId userId) {
        return query.findUser(userId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
