package com.acme.multi.ledger.use_cases.post_entry;

import com.acme.multi.ledger.types.AccountId;

public record PostEntry(AccountId id, long amountMinor) {
}
