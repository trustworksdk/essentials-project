package com.example.multi.ledger.events;

import com.example.multi.ledger.types.AccountId;

public record EntryPosted(AccountId id, long amountMinor) implements AccountEvent {
}
