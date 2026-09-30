package com.acme.multi.ledger.events;

import com.acme.multi.ledger.types.AccountId;

public sealed interface AccountEvent permits AccountOpened, EntryPosted {
    AccountId id();
}
