# Bounded context: banking

Intra-bank money transfer: moving money between two accounts that belong to the same bank.

**Write style: aggregate (`rules/slice-design.md` §R5).** Decisions live in `aggregates/`, on `Account` and
`IntraBankMoneyTransfer`, both `AggregateRoot`s reached through a `StatefulAggregateRepository`. This is a
sanctioned lane, not a legacy one — do **not** convert these to `Decider`s. Command slices still own
everything else: their command type, their one API file, their handler and their test.

## Slices

| Slice | Kind | Role |
|---|---|---|
| `use_cases/request_intra_bank_money_transfer` | command | Accepts the transfer intent, creates the `IntraBankMoneyTransfer` |
| `automations/transfer_money` | automation | The process manager that carries the transfer through its lifecycle |
| `views/account_balance` | view | Projects the running balance per account |

## Why two aggregates, one context

`Account` and `IntraBankMoneyTransfer` are separate consistency boundaries — no transaction writes both.
What binds them into one context is the transfer saga: `automations/transfer_money` chains their invariants,
and `TransactionId` flows through both event streams so each side can correlate. Each of its four handlers
writes exactly one aggregate; that is deliberate, and the reason this is an automation rather than a
command slice.

## Known gap: there is no `open_account` slice

`Accounts.openNewAccount(...)` is a slice-shaped hole. Opening an account has no command type, no handler
and no endpoint — the decision sits on the repository and is reachable only from the integration tests.
It was left that way on purpose during the slice migration: inventing a command with no caller would have
been new domain logic rather than restructuring. The practical consequence is that `views/account_balance`
has no API-driven way to create the account it projects.

If you add the slice, it goes in `use_cases/open_account/` and `openNewAccount` moves out of `Accounts`.

## Boundaries

The importable surface of this context is `events/` and `types/`, and nothing else. `aggregates/` is
BC-private. No other bounded context in this module imports anything from `banking` today, and that is
worth keeping true.

## A note on the command handler

`RequestIntraBankMoneyTransferHandler` reads the `Accounts` streams to check both accounts exist, while
writing a *different* aggregate. That is a consistency-boundary crossing and is racy by construction. It is
benign here only because an account can never be closed, so existence is monotone. If accounts ever become
closable, this needs a transaction-time existence projection instead — see the comment on the handler.

## Directories beside the slices

- `events/` — both sealed hierarchies, `AccountEvent` and `IntraBankMoneyTransferEvent`, one variant per file
- `types/` — ids, value objects, enums and the two exceptions
- `aggregates/` — the two aggregates plus their `StatefulAggregateRepository` wrappers
