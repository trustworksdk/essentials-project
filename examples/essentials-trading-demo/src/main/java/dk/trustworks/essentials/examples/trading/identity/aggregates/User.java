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

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.identity.events.*;
import dk.trustworks.essentials.examples.trading.identity.types.*;

import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A user's identity and lifecycle, event-sourced (design decision Q-2). The half of a user that has a history worth
 * keeping; the secret half — the password hash — is in {@link UserCredentials}, written in the same unit of work by the
 * slices that change both.
 * <p>
 * Every mutating method is an idempotent no-op when the state already holds, like the demo's other aggregates.
 * Reached through {@link Users}.
 */
public class User extends AggregateRoot<UserId, UserEvent, User> {
    private Email     email;
    private String    displayName;
    private Set<Role> roles;
    private boolean   locked;

    /**
     * Only for the JSON deserializer, should this aggregate ever be snapshotted: it keeps Jackson 3 from picking a
     * public constructor as an implicit properties creator (see the demo's root {@code CLAUDE.md}).
     */
    protected User() {
    }

    /**
     * Used for rehydration
     */
    public User(UserId userId) {
        super(userId);
    }

    public User(UserId userId, Email email, String displayName, Set<Role> roles) {
        super(userId);
        apply(new UserRegistered(userId, email, displayName, roles));
    }

    public void lock(String reason) {
        requireNonNull(reason, "No reason provided");
        if (locked) {
            return;
        }
        apply(new UserLocked(aggregateId(), reason));
    }

    public void unlock() {
        if (!locked) {
            return;
        }
        apply(new UserUnlocked(aggregateId()));
    }

    @EventHandler
    private void on(UserRegistered e) {
        email = e.email();
        displayName = e.displayName();
        roles = e.roles();
        locked = false;
    }

    @EventHandler
    private void on(UserLocked e) {
        locked = true;
    }

    @EventHandler
    private void on(UserUnlocked e) {
        locked = false;
    }
}
