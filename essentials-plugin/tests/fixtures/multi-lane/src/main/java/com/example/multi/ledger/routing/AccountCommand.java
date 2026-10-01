package com.example.multi.ledger.routing;

import com.example.multi.ledger.types.AccountId;

/** Routing marker for the LedgerAccounts aggregate type. */
public interface AccountCommand {
    AccountId id();
}
