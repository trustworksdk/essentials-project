# Slice: identity.register_user

**Kind:** command   **Status:** live
**Purpose:** Register a user: credentials row plus `UserRegistered`, in one unit of work.

## Invariants
- One user per email: the unique `email` column of `user_credentials` fails a duplicate, and the unit of work rolls
  the `UserRegistered` append back with it. Answered `409`.
- The password never leaves this slice in plaintext: `RegisterUser.toString()` masks it, and the command is sent
  with `send`, never `sendAndDontWait` (which would persist it in a durable queue). Keep both true.

## Endpoint
`POST /api/admin/users` (operator) — body is the `RegisterUser` command.
