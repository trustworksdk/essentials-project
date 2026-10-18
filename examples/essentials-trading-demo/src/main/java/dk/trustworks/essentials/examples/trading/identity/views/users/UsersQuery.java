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

import dk.trustworks.essentials.examples.trading.identity.types.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class UsersQuery {
    private static final String SELECT = "SELECT user_id, email, display_name, roles, locked, lock_reason FROM projection_users";

    private static final RowMapper<UserSummary> ROW = (rs, rowNum) -> new UserSummary(UserId.of(rs.getString("user_id")),
                                                                                       Email.of(rs.getString("email")),
                                                                                       rs.getString("display_name"),
                                                                                       Arrays.stream(rs.getString("roles").split(","))
                                                                                             .map(Role::valueOf)
                                                                                             .collect(Collectors.toUnmodifiableSet()),
                                                                                       rs.getBoolean("locked"),
                                                                                       rs.getString("lock_reason"));

    private final JdbcTemplate jdbcTemplate;

    public UsersQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    public List<UserSummary> users() {
        return jdbcTemplate.query(SELECT + " ORDER BY email", ROW);
    }

    public Optional<UserSummary> findUser(UserId userId) {
        requireNonNull(userId, "No userId provided");
        return jdbcTemplate.query(SELECT + " WHERE user_id = ?", ROW, userId.toString()).stream().findFirst();
    }
}
