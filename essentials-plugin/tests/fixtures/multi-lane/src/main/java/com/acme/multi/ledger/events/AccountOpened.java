package com.acme.multi.ledger.events;

import com.acme.multi.ledger.types.AccountId;

public record AccountOpened(AccountId id, String currency) implements AccountEvent {
}
