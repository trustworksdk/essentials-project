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

package dk.trustworks.essentials.examples.trading.identity.aggregates;

import dk.trustworks.essentials.examples.trading.identity.types.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The secret half of a {@link User}: the password hash, plus the login state Spring Security needs on every request
 * (email, roles, locked). A state-stored table, deliberately outside the event store — a credential must be replaceable
 * and erasable, and an event stream is neither (design decision Q-2).
 * <p>
 * It lives beside {@link User} in {@code aggregates/} because it is part of the same consistency boundary, not a second
 * concept: every slice that changes a user writes the aggregate and this table in one unit of work. Its unique email
 * column is also registration's uniqueness guard — a duplicate email fails the insert, and with it the whole
 * registration, so there is never an aggregate without credentials or credentials without an aggregate.
 * <p>
 * Read on every login by {@code config/TradingDemoSecurityConfiguration}, which is why it carries roles and the locked
 * flag itself rather than asking the eventually-consistent users view.
 */
@Component
public class UserCredentials {
    private final JdbcTemplate jdbcTemplate;

    public UserCredentials(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS user_credentials (
                                              user_id VARCHAR PRIMARY KEY,
                                              email VARCHAR NOT NULL UNIQUE,
                                              password_hash VARCHAR NOT NULL,
                                              roles VARCHAR NOT NULL,
                                              locked BOOLEAN NOT NULL DEFAULT FALSE,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
    }

    /**
     * What a login needs about one user.
     */
    public record StoredCredentials(UserId userId, Email email, String passwordHash, Set<Role> roles, boolean locked) {
    }

    /**
     * @throws EmailAlreadyRegisteredException when another user already logs in with this email
     */
    public void add(UserId userId, Email email, String passwordHash, Set<Role> roles) {
        requireNonNull(userId, "No userId provided");
        requireNonNull(email, "No email provided");
        requireNonNull(passwordHash, "No passwordHash provided");
        requireNonNull(roles, "No roles provided");
        try {
            jdbcTemplate.update("""
                                        INSERT INTO user_credentials (user_id, email, password_hash, roles, locked, updated_at)
                                        VALUES (?, ?, ?, ?, false, CURRENT_TIMESTAMP)
                                        """,
                                userId.toString(),
                                email.toString(),
                                passwordHash,
                                roles.stream().map(Role::name).sorted().collect(Collectors.joining(",")));
        } catch (DuplicateKeyException e) {
            throw new EmailAlreadyRegisteredException(email);
        }
    }

    public void setLocked(UserId userId, boolean locked) {
        requireNonNull(userId, "No userId provided");
        jdbcTemplate.update("UPDATE user_credentials SET locked = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                            locked,
                            userId.toString());
    }

    public Optional<StoredCredentials> findByEmail(Email email) {
        requireNonNull(email, "No email provided");
        return jdbcTemplate.query("SELECT user_id, email, password_hash, roles, locked FROM user_credentials WHERE email = ?",
                                  rs -> rs.next()
                                        ? Optional.of(new StoredCredentials(UserId.of(rs.getString("user_id")),
                                                                            Email.of(rs.getString("email")),
                                                                            rs.getString("password_hash"),
                                                                            Arrays.stream(rs.getString("roles").split(","))
                                                                                  .map(Role::valueOf)
                                                                                  .collect(Collectors.toUnmodifiableSet()),
                                                                            rs.getBoolean("locked")))
                                        : Optional.empty(),
                                  email.toString());
    }

    public boolean exists(Email email) {
        return findByEmail(email).isPresent();
    }
}
