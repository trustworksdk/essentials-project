package com.example.multi.ledger.events;

import com.example.multi.ledger.types.AccountId;

public sealed interface AccountEvent permits AccountOpened, EntryPosted {
    AccountId id();
}
