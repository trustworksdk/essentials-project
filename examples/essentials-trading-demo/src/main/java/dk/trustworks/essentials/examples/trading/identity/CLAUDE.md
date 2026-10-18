# Bounded context: identity

Users, their roles, and logging in. Design: `docs/design/phase-1-account-core.md` §2.

**Write style: aggregate (§R5)** — `User` is an `AggregateRoot` reached through `Users`, like every aggregate in the
demo. **Credentials are not in it** (design decision Q-2).

## Two stores, one user

| Store | Holds | Consistency | Read by |
|---|---|---|---|
| `Users` stream (`User` aggregate) | email, display name, roles, locked — the history | event-sourced | `views/users` |
| `user_credentials` table (`UserCredentials`) | password hash, plus email, roles and locked again | state-stored | login, on every request |

- **Every slice that changes a user writes both in one unit of work.** `register_user` inserts the row and appends
  `UserRegistered`; `lock_user`/`unlock_user` update the flag and append the event. Never one without the other.
- **The table's unique `email` column is registration's uniqueness guard.** A duplicate fails the insert, the unit of
  work rolls back, the API answers `409`.
- **No credential in an event, a durable queue or a log line.** `RegisterUser` masks its password in `toString()` and
  is sent with `send`, never `sendAndDontWait`. A password change will touch only the table; it is not a domain event.
- **Login reads the table, never the projection**, which is eventually consistent: a lock must apply to the very next
  request.

## Boundaries

Other contexts import `types/UserId` and nothing else. An account names its owner by `UserId`; it never sees an email.

## Slices

| Slice | Kind | Role |
|---|---|---|
| `use_cases/register_user` | command | Operator registers a user with an initial password |
| `use_cases/lock_user` / `unlock_user` | command | Operator stops / restores a user's access |
| `views/users` | view | Operator listing; no credentials |
