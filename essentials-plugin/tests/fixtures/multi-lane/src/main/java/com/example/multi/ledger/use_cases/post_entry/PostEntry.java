package com.example.multi.ledger.use_cases.post_entry;

import com.example.multi.ledger.types.AccountId;

public record PostEntry(AccountId id, long amountMinor) {
}
