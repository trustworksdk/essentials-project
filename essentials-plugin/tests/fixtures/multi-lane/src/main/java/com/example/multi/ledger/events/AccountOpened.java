package com.example.multi.ledger.events;

import com.example.multi.ledger.types.AccountId;

public record AccountOpened(AccountId id, String currency) implements AccountEvent {
}
