package com.acme.multi.ledger.events;

import com.acme.multi.ledger.types.AccountId;

public record EntryPosted(AccountId id, long amountMinor) implements AccountEvent {
}
